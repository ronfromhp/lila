package lila.fishnet

import org.joda.time.DateTime
import ornicar.scalalib.ThreadLocalRandom

import chess.Ply
import chess.format.{ Fen, Uci }
import chess.variant.Variant
import lila.common.IpAddress

sealed trait Work:
  def _id: Work.Id
  def game: Work.Game
  def tries: Int
  def lastTryByKey: Option[Client.Key]
  def acquired: Option[Work.Acquired]
  def createdAt: DateTime

  def skill: Client.Skill

  inline def id = _id

  def acquiredAt                   = acquired.map(_.date)
  def acquiredByKey                = acquired.map(_.clientKey)
  def isAcquiredBy(client: Client) = acquiredByKey contains client.key
  def isAcquired                   = acquired.isDefined
  def nonAcquired                  = !isAcquired
  def canAcquire(client: Client)   = lastTryByKey.fold(true)(client.key !=)

  def acquiredBefore(date: DateTime) = acquiredAt.??(_ isBefore date)

object Work:

  opaque type Id = String
  object Id extends OpaqueString[Id]

  case class Acquired(
      clientKey: Client.Key,
      userId: UserId,
      date: DateTime
  ):
    def ageInMillis       = nowMillis - date.getMillis
    override def toString = s"by $userId at $date"

  private[fishnet] case class Game(
      id: String, // can be a study chapter ID, if studyId is set
      initialFen: Option[Fen.Epd],
      studyId: Option[StudyId],
      variant: Variant,
      moves: String
  ):

    def uciList: List[Uci] = ~(Uci readList moves)

  case class Sender(
      userId: UserId,
      ip: Option[IpAddress],
      mod: Boolean,
      system: Boolean
  ):

    override def toString =
      if (system) lila.user.User.lichessId.value
      else userId.value

  case class Clock(wtime: Int, btime: Int, inc: chess.Clock.IncrementSeconds)

  case class Move(
      _id: Work.Id, // random
      game: Game,
      level: Int,
      clock: Option[Work.Clock]
  )

  case class Analysis(
      _id: Work.Id, // random
      sender: Sender,
      game: Game,
      startPly: Ply,
      tries: Int,
      lastTryByKey: Option[Client.Key],
      acquired: Option[Acquired],
      skipPositions: List[Int],
      createdAt: DateTime
  ) extends Work:

    def skill = Client.Skill.Analysis

    def assignTo(client: Client) =
      copy(
        acquired = Acquired(
          clientKey = client.key,
          userId = client.userId,
          date = DateTime.now
        ).some,
        lastTryByKey = client.key.some,
        tries = tries + 1
      )

    def timeout = copy(acquired = none)
    def invalid = copy(acquired = none)

    def isOutOfTries = tries >= 2

    def abort = copy(acquired = none)

    def nbMoves = game.moves.count(' ' ==) + 1

    override def toString =
      s"id:$id game:${game.id} variant:${game.variant} plies: ${game.moves.count(' ' ==)} tries:$tries requestedBy:$sender acquired:$acquired"

  def makeId = Id(ThreadLocalRandom nextString 8)
