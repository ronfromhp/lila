package lila.tournament

import chess.format.Fen
import com.softwaremill.tagging.*
import org.joda.time.DateTime
import org.joda.time.format.ISODateTimeFormat
import play.api.i18n.Lang
import play.api.libs.json.*
import scala.concurrent.duration.*
import scala.concurrent.ExecutionContext

import lila.common.Json.given
import lila.common.{ GreatPlayer, LightUser, Preload, Uptime }
import lila.game.{ Game, LightPov }
import lila.memo.CacheApi.*
import lila.memo.SettingStore
import lila.rating.PerfType
import lila.socket.{ SocketVersion, given }
import lila.user.{ LightUserApi, User }

final class JsonView(
    lightUserApi: LightUserApi,
    playerRepo: PlayerRepo,
    pairingRepo: PairingRepo,
    tournamentRepo: TournamentRepo,
    cached: TournamentCache,
    statsApi: TournamentStatsApi,
    shieldApi: TournamentShieldApi,
    cacheApi: lila.memo.CacheApi,
    proxyRepo: lila.round.GameProxyRepo,
    verify: Condition.Verify,
    duelStore: DuelStore,
    standingApi: TournamentStandingApi,
    pause: Pause,
    reloadEndpointSetting: SettingStore[String] @@ TournamentReloadEndpoint
)(using ec: ExecutionContext):

  import JsonView.*
  import Condition.JSONHandlers.given
  private given Ordering[TeamId] = stringOrdering

  def apply(
      tour: Tournament,
      page: Option[Int],
      me: Option[User],
      getUserTeamIds: User => Fu[List[TeamId]],
      getTeamName: TeamId => Option[String],
      playerInfoExt: Option[PlayerInfoExt],
      socketVersion: Option[SocketVersion],
      partial: Boolean,
      withScores: Boolean,
      myInfo: Preload[Option[MyInfo]] = Preload.none
  )(using lang: Lang): Fu[JsObject] =
    for {
      data   <- cachableData get tour.id
      myInfo <- myInfo.orLoad(me ?? { fetchMyInfo(tour, _) })
      pauseDelay = me flatMap { u =>
        pause.remainingDelay(u.id, tour)
      }
      full = !partial
      stand <- standingApi(
        tour,
        (myInfo, page) match {
          case (_, Some(p)) => p
          case (Some(i), _) => i.page
          case _            => 1
        },
        withScores = withScores
      )
      playerInfoJson <- playerInfoExt ?? { pie =>
        playerInfoExtended(tour, pie).map(_.some)
      }
      verdicts <- full ?? {
        (me, myInfo) match
          case (None, _)                                   => fuccess(tour.conditions.accepted.some)
          case (Some(_), Some(myInfo)) if !myInfo.withdraw => fuccess(tour.conditions.accepted.some)
          case (Some(user), Some(_)) => verify.rejoin(tour.conditions, user, getUserTeamIds) map some
          case (Some(user), None)    => verify(tour.conditions, user, getUserTeamIds) map some
      }
      stats       <- statsApi(tour)
      shieldOwner <- full.?? { shieldApi currentOwner tour }
      teamsToJoinWith <- full.??(~(for {
        u <- me; battle <- tour.teamBattle
      } yield getUserTeamIds(u) map { teams =>
        battle.teams.intersect(teams.toSet).toList
      }))
      teamStanding <- getTeamStanding(tour)
      myTeam       <- myInfo.flatMap(_.teamId) ?? { getMyRankedTeam(tour, _) }
    } yield commonTournamentJson(tour, data, stats, teamStanding) ++ Json
      .obj("standing" -> stand)
      .add("me" -> myInfo.map(myInfoJson(me, pauseDelay)))
      .add("playerInfo" -> playerInfoJson)
      .add("socketVersion" -> socketVersion)
      .add("myTeam" -> myTeam) ++
      full.?? {
        Json
          .obj(
            "id"        -> tour.id,
            "createdBy" -> tour.createdBy,
            "startsAt"  -> formatDate(tour.startsAt),
            "system"    -> "arena", // BC
            "fullName"  -> tour.name(),
            "minutes"   -> tour.minutes,
            "perf"      -> tour.perfType,
            "clock"     -> tour.clock,
            "variant"   -> tour.variant.key,
            "rated"     -> tour.isRated
          )
          .add("spotlight" -> tour.spotlight)
          .add("berserkable" -> tour.berserkable)
          .add("noStreak" -> tour.noStreak)
          .add("position" -> tour.position.ifTrue(full).map(positionJson))
          .add("verdicts" -> verdicts.map(Condition.JSONHandlers.verdictsFor(_, lang)))
          .add("schedule" -> tour.schedule.map(scheduleJson))
          .add("private" -> tour.isPrivate)
          .add("quote" -> tour.isCreated.option(lila.quote.Quote.one(tour.id.value)))
          .add("defender" -> shieldOwner)
          .add("greatPlayer" -> GreatPlayer.wikiUrl(tour.name).map { url =>
            Json.obj("name" -> tour.name, "url" -> url)
          })
          .add("teamBattle" -> tour.teamBattle.map { battle =>
            Json
              .obj(
                "teams" -> JsObject(battle.sortedTeamIds.map { id =>
                  id.value -> JsString(getTeamName(id).getOrElse(id.value))
                }),
                "nbLeaders" -> battle.nbLeaders
              )
              .add("joinWith" -> me.isDefined.option(teamsToJoinWith.sorted))
          })
          .add("description" -> tour.description)
          .add("myUsername" -> me.map(_.username))
          .add[Condition.RatingCondition]("minRating", tour.conditions.minRating)
          .add[Condition.RatingCondition]("maxRating", tour.conditions.maxRating)
          .add("minRatedGames", tour.conditions.nbRatedGame)
          .add("onlyTitled", tour.conditions.titled.isDefined)
          .add("teamMember", tour.conditions.teamMember.map(_.teamId))
      }

  def addReloadEndpoint(js: JsObject, tour: Tournament, useLilaHttp: Tournament => Boolean) =
    js + ("reloadEndpoint" -> JsString({
      if (useLilaHttp(tour)) reloadEndpointSetting.get() else reloadEndpointSetting.default
    }.replace("{id}", tour.id.value)))

  def clearCache(tour: Tournament): Unit =
    standingApi clearCache tour
    cachableData invalidate tour.id

  def fetchMyInfo(tour: Tournament, me: User): Fu[Option[MyInfo]] =
    playerRepo.find(tour.id, me.id) flatMap {
      _ ?? { player =>
        fetchCurrentGameId(tour, me) flatMap { gameId =>
          getOrGuessRank(tour, player) dmap { rank =>
            MyInfo(rank + 1, player.withdraw, gameId, player.team).some
          }
        }
      }
    }

  // if the user is not yet in the cached ranking,
  // guess its rank based on other players scores in the DB
  private def getOrGuessRank(tour: Tournament, player: Player): Fu[Rank] =
    cached ranking tour flatMap {
      _.ranking get player.userId match
        case Some(rank) => fuccess(rank)
        case None       => playerRepo.computeRankOf(player)
    }

  def playerInfoExtended(tour: Tournament, info: PlayerInfoExt): Fu[JsObject] =
    for {
      ranking <- cached ranking tour
      sheet   <- cached.sheet(tour, info.userId)
      user    <- lightUserApi.asyncFallback(info.userId)
    } yield info match
      case PlayerInfoExt(_, player, povs) =>
        val isPlaying = povs.headOption.??(_.game.playable)
        val povScores: List[(LightPov, Option[arena.Sheet.Score])] = povs zip {
          (isPlaying ?? List(none[arena.Sheet.Score])) ::: sheet.scores.map(some)
        }
        Json.obj(
          "player" -> Json
            .obj(
              "id"     -> user.id,
              "name"   -> user.name,
              "rating" -> player.rating,
              "score"  -> player.score,
              "fire"   -> player.fire,
              "nb"     -> sheetNbs(sheet)
            )
            .add("title" -> user.title)
            .add("performance" -> player.performanceOption)
            .add("rank" -> ranking.ranking.get(user.id).map(_ + 1))
            .add("provisional" -> player.provisional)
            .add("withdraw" -> player.withdraw)
            .add("team" -> player.team),
          "pairings" -> povScores.map { case (pov, score) =>
            Json
              .obj(
                "id"     -> pov.gameId,
                "color"  -> pov.color.name,
                "op"     -> gameUserJson(pov.opponent.userId, pov.opponent.rating),
                "win"    -> score.flatMap(_.isWin),
                "status" -> pov.game.status.id,
                "score"  -> score.map(_.value)
              )
              .add("berserk" -> pov.player.berserk)
          }
        )

  private def fetchCurrentGameId(tour: Tournament, user: User): Fu[Option[GameId]] =
    if (Uptime.startedSinceSeconds(60)) fuccess(duelStore.find(tour, user))
    else pairingRepo.playingByTourAndUserId(tour.id, user.id)

  private def fetchFeaturedGame(tour: Tournament): Fu[Option[FeaturedGame]] =
    tour.featuredId.ifTrue(tour.isStarted) ?? pairingRepo.byId flatMap {
      _ ?? { pairing =>
        proxyRepo game pairing.gameId flatMap {
          _ ?? { game =>
            cached ranking tour flatMap { ranking =>
              playerRepo.pairByTourAndUserIds(tour.id, pairing.user1, pairing.user2) map { pairOption =>
                for {
                  (p1, p2) <- pairOption
                  rp1      <- RankedPlayer(ranking.ranking)(p1)
                  rp2      <- RankedPlayer(ranking.ranking)(p2)
                } yield FeaturedGame(game, rp1, rp2)
              }
            }
          }
        }
      }
    }

  private def sheetNbs(s: arena.Sheet) =
    Json.obj(
      "game"    -> s.scores.size,
      "berserk" -> s.scores.count(_.isBerserk),
      "win"     -> s.scores.count(_.res == arena.Sheet.Result.Win)
    )

  private def duelsJson(tourId: TourId): Fu[(List[Duel], JsArray)] =
    val duels = duelStore.bestRated(tourId, 6)
    (duels.map(duelJson).sequenceFu: Fu[List[JsObject]]) map { jsons =>
      (duels, JsArray(jsons))
    }

  private[tournament] val cachableData =
    cacheApi[TourId, CachableData](64, "tournament.json.cachable") {
      _.expireAfterWrite(1 second)
        .buildAsyncFuture { id =>
          for {
            tour               <- cached.tourCache byId id
            (duels, jsonDuels) <- duelsJson(id)
            duelTeams <- tour.exists(_.isTeamBattle) ?? {
              playerRepo.teamsOfPlayers(id, duels.flatMap(_.userIds)) map { teams =>
                JsObject(teams map { (userId, teamId) =>
                  (userId.value, JsString(teamId.value))
                }).some
              }
            }
            featured <- tour ?? fetchFeaturedGame
            podium   <- tour.exists(_.isFinished) ?? podiumJsonCache.get(id)
          } yield CachableData(
            duels = jsonDuels,
            duelTeams = duelTeams,
            featured = featured map featuredJson,
            podium = podium
          )
        }
    }

  private def featuredJson(featured: FeaturedGame) =
    val game = featured.game
    def ofPlayer(rp: RankedPlayer, p: lila.game.Player) =
      val light = lightUserApi syncFallback rp.player.userId
      Json
        .obj(
          "rank"   -> rp.rank,
          "name"   -> light.name,
          "rating" -> rp.player.rating
        )
        .add("title" -> light.title)
        .add("berserk" -> p.berserk)
    Json
      .obj(
        "id"          -> game.id,
        "fen"         -> chess.format.Fen.writeBoardAndColor(game.situation),
        "orientation" -> game.naturalOrientation.name,
        "color"    -> game.naturalOrientation.name, // app BC https://github.com/lichess-org/lila/issues/7195
        "lastMove" -> (game.lastMoveKeys | ""),
        "white"    -> ofPlayer(featured.white, game player chess.White),
        "black"    -> ofPlayer(featured.black, game player chess.Black)
      )
      .add(
        // not named `clock` to avoid conflict with lichobile
        "c" -> game.clock.ifTrue(game.isBeingPlayed).map { c =>
          Json.obj(
            "white" -> c.remainingTime(chess.White).roundSeconds,
            "black" -> c.remainingTime(chess.Black).roundSeconds
          )
        }
      )
      .add("winner" -> game.winnerColor.map(_.name))

  private def myInfoJson(u: Option[User], delay: Option[Pause.Delay])(i: MyInfo) =
    Json
      .obj("rank" -> i.rank)
      .add("withdraw", i.withdraw)
      .add("gameId", i.gameId)
      .add("pauseDelay", delay)

  private def gameUserJson(userId: Option[UserId], rating: Option[IntRating]): JsObject =
    val light = userId flatMap lightUserApi.sync
    Json
      .obj("rating" -> rating)
      .add("name" -> light.map(_.name))
      .add("title" -> light.flatMap(_.title))

  private val podiumJsonCache = cacheApi[TourId, Option[JsArray]](32, "tournament.podiumJson") {
    _.expireAfterAccess(15 seconds)
      .expireAfterWrite(1 minute)
      .maximumSize(256)
      .buildAsyncFuture { id =>
        tournamentRepo finishedById id flatMap {
          _ ?? { tour =>
            playerRepo.bestByTourWithRank(id, 3).flatMap { top3 =>
              // check that the winner is still correctly denormalized
              top3.headOption.map(_.player.userId).filter(w => tour.winnerId.fold(true)(w !=)) foreach {
                tournamentRepo.setWinnerId(tour.id, _)
              }
              top3.map { case rp @ RankedPlayer(_, player) =>
                for {
                  sheet <- cached.sheet(tour, player.userId)
                  json <- playerJson(
                    lightUserApi,
                    none,
                    rp,
                    streakable = tour.streakable,
                    withScores = false
                  )
                } yield json ++ Json
                  .obj("nb" -> sheetNbs(sheet))
                  .add("performance" -> player.performanceOption)
              }.sequenceFu: Fu[List[JsObject]]
            } map { l =>
              JsArray(l).some
            }
          }
        }
      }
  }

  private def duelPlayerJson(p: Duel.DuelPlayer): Fu[JsObject] =
    lightUserApi.asyncFallback(p.name.id) map { u =>
      Json
        .obj(
          "n" -> u.name,
          "r" -> p.rating.value,
          "k" -> p.rank.value
        )
        .add("t" -> u.title)
    }

  private def duelJson(d: Duel): Fu[JsObject] =
    for {
      u1 <- duelPlayerJson(d.p1)
      u2 <- duelPlayerJson(d.p2)
    } yield Json.obj(
      "id" -> d.gameId,
      "p"  -> Json.arr(u1, u2)
    )

  def getTeamStanding(tour: Tournament): Fu[Option[JsArray]] =
    tour.isTeamBattle ?? { teamStandingJsonCache get tour.id dmap some }

  def apiTeamStanding(tour: Tournament): Fu[Option[JsArray]] =
    tour.teamBattle ?? { battle =>
      if (battle.hasTooManyTeams) bigTeamStandingJsonCache get tour.id dmap some
      else teamStandingJsonCache get tour.id dmap some
    }

  private val teamStandingJsonCache = cacheApi[TourId, JsArray](4, "tournament.teamStanding") {
    _.expireAfterWrite(500 millis)
      .buildAsyncFuture(fetchAndRenderTeamStandingJson(TeamBattle.displayTeams))
  }

  private val bigTeamStandingJsonCache = cacheApi[TourId, JsArray](4, "tournament.teamStanding.big") {
    _.expireAfterWrite(2 seconds)
      .buildAsyncFuture(fetchAndRenderTeamStandingJson(TeamBattle.maxTeams))
  }

  private[tournament] def fetchAndRenderTeamStandingJson(max: Int)(id: TourId) =
    cached.battle.teamStanding.get(id) map { ranked =>
      JsArray(ranked take max map teamBattleRankedWrites.writes)
    }

  implicit private val teamBattleRankedWrites: Writes[TeamBattle.RankedTeam] = OWrites { rt =>
    Json.obj(
      "rank"  -> rt.rank,
      "id"    -> rt.teamId,
      "score" -> rt.score,
      "players" -> rt.leaders.map { p =>
        Json.obj(
          "user"  -> lightUserApi.sync(p.userId),
          "score" -> p.score
        )
      }
    )
  }

  private def getMyRankedTeam(tour: Tournament, teamId: TeamId): Fu[Option[TeamBattle.RankedTeam]] =
    tour.teamBattle.exists(_.hasTooManyTeams) ??
      cached.battle.teamStanding.get(tour.id) map {
        _.find(_.teamId == teamId)
      }

  private val teamInfoCache =
    cacheApi[(TourId, TeamId), Option[JsObject]](16, "tournament.teamInfo.json") {
      _.expireAfterWrite(5 seconds)
        .maximumSize(32)
        .buildAsyncFuture { case (tourId, teamId) =>
          cached.teamInfo.get(tourId -> teamId) flatMap {
            _ ?? { info =>
              lightUserApi.preloadMany(info.topPlayers.map(_.userId)) inject Json
                .obj(
                  "id"        -> teamId,
                  "nbPlayers" -> info.nbPlayers,
                  "rating"    -> info.avgRating,
                  "perf"      -> info.avgPerf,
                  "score"     -> info.avgScore,
                  "topPlayers" -> info.topPlayers.flatMap { p =>
                    lightUserApi.sync(p.userId) map { user =>
                      Json
                        .obj(
                          "name"   -> user.name,
                          "rating" -> p.rating,
                          "score"  -> p.score
                        )
                        .add("fire" -> p.fire)
                        .add("title" -> user.title)
                    }
                  }
                )
                .some
            }
          }
        }
    }

  def teamInfo(tour: Tournament, teamId: TeamId): Fu[Option[JsObject]] =
    tour.isTeamBattle ?? {
      teamInfoCache get (tour.id -> teamId)
    }

  private[tournament] def commonTournamentJson(
      tour: Tournament,
      data: CachableData,
      stats: Option[TournamentStats],
      teamStanding: Option[JsArray]
  ): JsObject =
    Json
      .obj(
        "nbPlayers" -> tour.nbPlayers,
        "duels"     -> data.duels
      )
      .add("secondsToFinish" -> tour.isStarted.option(tour.secondsToFinish))
      .add("secondsToStart" -> tour.isCreated.option(tour.secondsToStart))
      .add("isStarted" -> tour.isStarted)
      .add("isFinished" -> tour.isFinished)
      .add("isRecentlyFinished" -> tour.isRecentlyFinished)
      .add("featured" -> data.featured)
      .add("podium" -> data.podium)
      .add("pairingsClosed" -> tour.pairingsClosed)
      .add("stats" -> stats)
      .add("teamStanding" -> teamStanding)
      .add("duelTeams" -> data.duelTeams)

object JsonView:

  private[tournament] case class CachableData(
      duels: JsArray,
      duelTeams: Option[JsObject],
      featured: Option[JsObject],
      podium: Option[JsArray]
  )

  def top(t: TournamentTop, getLightUser: LightUser.GetterSync): JsArray =
    JsArray {
      t.value.map { p =>
        val light = getLightUser(p.userId)
        Json
          .obj(
            "n" -> light.fold(p.userId into UserName)(_.name),
            "s" -> p.score
          )
          .add("t" -> light.flatMap(_.title))
          .add("f" -> p.fire)
          .add("w" -> p.withdraw)
      }
    }

  val playerResultWrites: OWrites[Player.Result] = OWrites[Player.Result] {
    case Player.Result(player, user, rank, sheet) =>
      Json
        .obj(
          "rank"     -> rank,
          "score"    -> player.score,
          "rating"   -> player.rating,
          "username" -> user.name
        )
        .add("title" -> user.title)
        .add("performance" -> player.performanceOption)
        .add("team" -> player.team)
        .add("sheet", sheet.map(sheetJson(streakFire = false, withScores = true)))
  }

  def playerJson(
      lightUserApi: LightUserApi,
      sheets: Map[UserId, arena.Sheet],
      streakable: Boolean,
      withScores: Boolean
  )(rankedPlayer: RankedPlayer)(using ec: ExecutionContext): Fu[JsObject] =
    playerJson(
      lightUserApi,
      sheets get rankedPlayer.player.userId,
      rankedPlayer,
      streakable = streakable,
      withScores = withScores
    )

  private[tournament] def playerJson(
      lightUserApi: LightUserApi,
      sheet: Option[arena.Sheet],
      rankedPlayer: RankedPlayer,
      streakable: Boolean,
      withScores: Boolean
  )(using ExecutionContext): Fu[JsObject] =
    val p = rankedPlayer.player
    lightUserApi asyncFallback p.userId map { light =>
      Json
        .obj(
          "name"   -> light.name,
          "rank"   -> rankedPlayer.rank,
          "rating" -> p.rating,
          "score"  -> p.score
        )
        .add("sheet", sheet.map(sheetJson(streakFire = streakable, withScores = withScores)))
        .add("title" -> light.title)
        .add("provisional" -> p.provisional)
        .add("withdraw" -> p.withdraw)
        .add("team" -> p.team)
    }

  private[tournament] def sheetJson(streakFire: Boolean, withScores: Boolean)(s: arena.Sheet) =
    Json
      .obj()
      .add("scores", withScores option s.scoresToString)
      .add("fire", streakFire && s.isOnFire)

  private def formatDate(date: DateTime) = ISODateTimeFormat.dateTime print date

  private[tournament] def scheduleJson(s: Schedule) =
    Json.obj(
      "freq"  -> s.freq.name,
      "speed" -> s.speed.key
    )

  implicit val clockWrites: OWrites[chess.Clock.Config] = OWrites { clock =>
    Json.obj(
      "limit"     -> clock.limitSeconds,
      "increment" -> clock.incrementSeconds
    )
  }

  private[tournament] def positionJson(fen: Fen.Opening): JsObject =
    Thematic.byFen(fen) match
      case Some(pos) =>
        Json
          .obj(
            "eco"  -> pos.eco,
            "name" -> pos.name,
            "fen"  -> pos.fen,
            "url"  -> pos.url
          )
      case None =>
        Json
          .obj(
            "name" -> "Custom position",
            "fen"  -> fen
          )

  implicit private[tournament] val spotlightWrites: OWrites[Spotlight] = OWrites { s =>
    Json
      .obj(
        "headline"    -> s.headline,
        "description" -> s.description
      )
      .add("iconImg" -> s.iconImg)
      .add("iconFont" -> s.iconFont)
  }

  implicit private[tournament] def perfTypeWrites(using lang: Lang): OWrites[PerfType] =
    OWrites { pt =>
      Json
        .obj("key" -> pt.key, "name" -> pt.trans)
        .add("icon" -> mobileBcIcons.get(pt)) // mobile BC only
    }

  implicit private[tournament] val statsWrites: Writes[TournamentStats] = Json.writes[TournamentStats]

  private[tournament] val mobileBcIcons: Map[PerfType, String] = Map(
    PerfType.UltraBullet -> "{",
    PerfType.Bullet      -> "T",
    PerfType.Blitz       -> ")",
    PerfType.Rapid       -> "#",
    PerfType.Classical   -> "+"
  )
