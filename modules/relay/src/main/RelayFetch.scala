package lila.relay

import akka.actor.*
import chess.format.pgn.{ Tags, SanStr, PgnStr }
import com.github.blemale.scaffeine.LoadingCache
import io.mola.galimatias.URL
import org.joda.time.DateTime
import play.api.libs.json.*
import play.api.libs.ws.StandaloneWSClient
import RelayRound.Sync.{ UpstreamIds, UpstreamUrl }
import scala.concurrent.duration.*
import scala.concurrent.ExecutionContext

import lila.base.LilaInvalid
import lila.common.LilaScheduler
import lila.game.{ GameRepo, PgnDump }
import lila.memo.CacheApi
import lila.round.GameProxyRepo
import lila.study.MultiPgn
import lila.tree.Node.Comments

final private class RelayFetch(
    sync: RelaySync,
    api: RelayApi,
    irc: lila.irc.IrcApi,
    formatApi: RelayFormatApi,
    gameRepo: GameRepo,
    pgnDump: PgnDump,
    gameProxy: GameProxyRepo,
    ws: StandaloneWSClient
)(using ExecutionContext, akka.actor.Scheduler):

  LilaScheduler("RelayFetch.official", _.Every(500 millis), _.AtMost(15 seconds), _.Delay(30 seconds)) {
    syncRelays(official = true)
  }

  LilaScheduler("RelayFetch.user", _.Every(750 millis), _.AtMost(10 seconds), _.Delay(1 minute)) {
    syncRelays(official = false)
  }

  private def syncRelays(official: Boolean) =
    api
      .toSync(official)
      .flatMap { relays =>
        lila.mon.relay.ongoing(official).update(relays.size)
        relays.map { rt =>
          if (rt.round.sync.ongoing)
            processRelay(rt) flatMap { newRelay =>
              api.update(rt.round)(_ => newRelay)
            }
          else if (rt.round.hasStarted)
            logger.info(s"Finish by lack of activity ${rt.round}")
            api.update(rt.round)(_.finish)
          else if (rt.round.shouldGiveUp)
            val msg = "Finish for lack of start"
            logger.info(s"$msg ${rt.round}")
            if (rt.tour.official) irc.broadcastError(rt.round.id.value, rt.fullName, msg)
            api.update(rt.round)(_.finish)
          else fuccess(rt.round)
        }.sequenceFu
      }
      .void

  // no writing the relay; only reading!
  private def processRelay(rt: RelayRound.WithTour): Fu[RelayRound] =
    if (!rt.round.sync.playing) fuccess(rt.round.withSync(_.play))
    else
      fetchGames(rt)
        .map(games => rt.tour.players.fold(games)(_ update games))
        .mon(_.relay.fetchTime(rt.tour.official, rt.round.slug))
        .addEffect(gs => lila.mon.relay.games(rt.tour.official, rt.round.slug).update(gs.size).unit)
        .flatMap { games =>
          sync(rt, games)
            .withTimeoutError(7 seconds, SyncResult.Timeout)
            .mon(_.relay.syncTime(rt.tour.official, rt.round.slug))
            .map { res =>
              res -> rt.round
                .withSync(_ addLog SyncLog.event(res.nbMoves, none))
                .copy(finished = games.forall(_.end.isDefined))
            }
        }
        .recover { case e: Exception =>
          (e match {
            case SyncResult.Timeout =>
              if (rt.tour.official) logger.info(s"Sync timeout ${rt.round}")
              SyncResult.Timeout
            case _ =>
              if (rt.tour.official) logger.info(s"Sync error ${rt.round} ${e.getMessage take 80}")
              SyncResult.Error(e.getMessage)
          }) -> rt.round.withSync(_ addLog SyncLog.event(0, e.some))
        }
        .map { case (result, newRelay) =>
          afterSync(result, newRelay withTour rt.tour)
        }

  private def afterSync(result: SyncResult, rt: RelayRound.WithTour): RelayRound =
    result match
      case result: SyncResult.Ok if result.nbMoves == 0 => continueRelay(rt)
      case result: SyncResult.Ok =>
        continueRelay(rt)
        lila.mon.relay.moves(rt.tour.official, rt.round.slug).increment(result.nbMoves)
        continueRelay(rt.round.ensureStarted.resume withTour rt.tour)
      case _ => continueRelay(rt)

  private def continueRelay(rt: RelayRound.WithTour): RelayRound =
    rt.round.sync.upstream.fold(rt.round) { upstream =>
      val seconds =
        if (rt.round.sync.log.alwaysFails)
          rt.round.sync.log.events.lastOption
            .filterNot(_.isTimeout)
            .flatMap(_.error)
            .ifTrue(rt.tour.official && rt.round.shouldHaveStarted)
            .filterNot(_ contains "Cannot parse moves")
            .filterNot(_ contains "Found an empty PGN")
            .foreach { irc.broadcastError(rt.round.id.value, rt.fullName, _) }
          60
        else
          rt.round.sync.delay getOrElse {
            if (upstream.local) 3 else 6
          }
      rt.round.withSync {
        _.copy(
          nextAt = DateTime.now plusSeconds {
            seconds atLeast {
              if (rt.round.sync.log.justTimedOut) 10 else 2
            }
          } some
        )
      }
    }

  import com.github.benmanes.caffeine.cache.Cache
  import RelayFetch.GamesSeenBy

  private val gameIdsUpstreamPgnFlags = PgnDump.WithFlags(
    clocks = true,
    moves = true,
    tags = true,
    evals = false,
    opening = false,
    literate = false,
    pgnInJson = false,
    delayMoves = true
  )

  private def fetchGames(rt: RelayRound.WithTour): Fu[RelayGames] =
    rt.round.sync.upstream ?? {
      case UpstreamIds(ids) =>
        gameRepo.gamesFromSecondary(ids) flatMap
          gameProxy.upgradeIfPresent flatMap
          gameRepo.withInitialFens flatMap { games =>
            if (games.size == ids.size)
              games.map { case (game, fen) =>
                pgnDump(game, fen, gameIdsUpstreamPgnFlags).dmap(_.render)
              }.sequenceFu dmap MultiPgn.apply
            else
              throw LilaInvalid(
                s"Invalid game IDs: ${ids.filter(id => !games.exists(_._1.id == id)) mkString ", "}"
              )
          } flatMap RelayFetch.multiPgnToGames.apply
      case url: UpstreamUrl =>
        cache.asMap
          .compute(
            url,
            (_, v) =>
              Option(v) match {
                case Some(GamesSeenBy(games, seenBy)) if !seenBy(rt.round.id) =>
                  GamesSeenBy(games, seenBy + rt.round.id)
                case _ =>
                  GamesSeenBy(doFetchUrl(url, RelayFetch.maxChapters(rt.tour)), Set(rt.round.id))
              }
          )
          .games
    }

  // The goal of this is to make sure that an upstream used by several broadcast
  // is only pulled from as many times as necessary, and not more.
  private val cache: Cache[UpstreamUrl, GamesSeenBy] = CacheApi.scaffeineNoScheduler
    .initialCapacity(4)
    .maximumSize(32)
    .build[UpstreamUrl, GamesSeenBy]()
    .underlying

  private def doFetchUrl(upstream: UpstreamUrl, max: Int): Fu[RelayGames] =
    import RelayFetch.DgtJson.*
    formatApi get upstream.withRound flatMap {
      case RelayFormat.SingleFile(doc) =>
        doc.format match
          // all games in a single PGN file
          case RelayFormat.DocFormat.Pgn => httpGetPgn(doc.url) map { MultiPgn.split(_, max) }
          // maybe a single JSON game? Why not
          case RelayFormat.DocFormat.Json =>
            httpGetJson[GameJson](doc.url) map { game =>
              MultiPgn(List(game.toPgn()))
            }
      case RelayFormat.ManyFiles(indexUrl, makeGameDoc) =>
        httpGetJson[RoundJson](indexUrl) flatMap { round =>
          round.pairings.zipWithIndex
            .map { case (pairing, i) =>
              val number  = i + 1
              val gameDoc = makeGameDoc(number)
              (gameDoc.format match {
                case RelayFormat.DocFormat.Pgn => httpGetPgn(gameDoc.url)
                case RelayFormat.DocFormat.Json =>
                  httpGetJson[GameJson](gameDoc.url).recover { case _: Exception =>
                    GameJson(moves = Nil, result = none)
                  } map { _.toPgn(pairing.tags) }
              }) map (number -> _)
            }
            .sequenceFu
            .map { results =>
              MultiPgn(results.sortBy(_._1).map(_._2))
            }
        }
    } flatMap RelayFetch.multiPgnToGames.apply

  private def httpGet(url: URL): Fu[String] =
    ws.url(url.toString)
      .withRequestTimeout(4.seconds)
      .get()
      .flatMap {
        case res if res.status == 200 => fuccess(res.body)
        case res                      => fufail(s"[${res.status}] $url")
      }

  private def httpGetPgn(url: URL): Fu[PgnStr] = PgnStr from httpGet(url)

  private def httpGetJson[A: Reads](url: URL): Fu[A] =
    for {
      str  <- httpGet(url)
      json <- scala.concurrent.Future(Json parse str) // Json.parse throws exceptions (!)
      data <-
        implicitly[Reads[A]]
          .reads(json)
          .fold(
            err => fufail(s"Invalid JSON from $url: $err"),
            fuccess
          )
    } yield data

private object RelayFetch:

  case class GamesSeenBy(games: Fu[RelayGames], seenBy: Set[RelayRoundId])

  def maxChapters(tour: RelayTour) =
    lila.study.Study.maxChapters * (if (tour.official) 2 else 1)

  private object DgtJson:
    case class PairingPlayer(
        fname: Option[String],
        mname: Option[String],
        lname: Option[String],
        title: Option[String]
    ):
      def fullName =
        some {
          List(fname, mname, lname).flatten mkString " "
        }.filter(_.nonEmpty)
    case class RoundJsonPairing(white: PairingPlayer, black: PairingPlayer, result: String):
      import chess.format.pgn.*
      def tags =
        Tags(
          List(
            white.fullName map { v =>
              Tag(_.White, v)
            },
            white.title map { v =>
              Tag(_.WhiteTitle, v)
            },
            black.fullName map { v =>
              Tag(_.Black, v)
            },
            black.title map { v =>
              Tag(_.BlackTitle, v)
            },
            Tag(_.Result, result).some
          ).flatten
        )
    case class RoundJson(pairings: List[RoundJsonPairing])
    given Reads[PairingPlayer]    = Json.reads
    given Reads[RoundJsonPairing] = Json.reads
    given Reads[RoundJson]        = Json.reads

    case class GameJson(moves: List[String], result: Option[String]):
      def toPgn(extraTags: Tags = Tags.empty) =
        val strMoves = moves.map(_ split ' ') map { move =>
          chess.format.pgn.Move(
            san = SanStr(~move.headOption),
            secondsLeft = move.lift(1).map(_.takeWhile(_.isDigit)) flatMap (_.toIntOption)
          )
        } mkString " "
        PgnStr(s"$extraTags\n\n$strMoves")
    given Reads[GameJson] = Json.reads

  object multiPgnToGames:

    import scala.util.{ Failure, Success, Try }

    def apply(multiPgn: MultiPgn): Fu[Vector[RelayGame]] =
      multiPgn.value
        .foldLeft[Try[(Vector[RelayGame], Int)]](Success(Vector.empty -> 0)) {
          case (Success((acc, index)), pgn) =>
            pgnCache.get(pgn) flatMap { f =>
              val game = f(index)
              if (game.isEmpty) Failure(LilaInvalid(s"Found an empty PGN at index $index"))
              else Success((acc :+ game, index + 1))
            }
          case (acc, _) => acc
        }
        .future
        .dmap(_._1)

    private val pgnCache: LoadingCache[PgnStr, Try[Int => RelayGame]] = CacheApi.scaffeineNoScheduler
      .expireAfterAccess(2 minutes)
      .maximumSize(512)
      .build(compute)

    private def compute(pgn: PgnStr): Try[Int => RelayGame] =
      lila.study
        .PgnImport(pgn, Nil)
        .fold(
          err => Failure(LilaInvalid(err)),
          res =>
            Success(index =>
              RelayGame(
                index = index,
                tags = res.tags,
                variant = res.variant,
                root = res.root.copy(
                  comments = Comments.empty,
                  children = res.root.children.updateMainline(_.copy(comments = Comments.empty))
                ),
                end = res.end
              )
            )
        )
