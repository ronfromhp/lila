package lila.explorer

import com.softwaremill.macwire.*
import play.api.Configuration

case class InternalEndpoint(value: String) extends AnyVal with StringValue

@Module
final class Env(
    appConfig: Configuration,
    gameRepo: lila.game.GameRepo,
    userRepo: lila.user.UserRepo,
    gameImporter: lila.importer.Importer,
    getBotUserIds: lila.user.GetBotIds,
    settingStore: lila.memo.SettingStore.Builder,
    ws: play.api.libs.ws.StandaloneWSClient
)(using
    ec: scala.concurrent.ExecutionContext,
    system: akka.actor.ActorSystem,
    materializer: akka.stream.Materializer
):

  private lazy val internalEndpoint = InternalEndpoint {
    appConfig.get[String]("explorer.internal_endpoint")
  }

  private lazy val indexer: ExplorerIndexer = wire[ExplorerIndexer]

  lazy val importer = wire[ExplorerImporter]

  lazy val indexFlowSetting = settingStore[Boolean](
    "explorerIndexFlow",
    default = false,
    text = "Explorer: index new games as soon as they complete".some
  )

  lila.common.Bus.subscribeFun("finishGame") {
    case lila.game.actorApi.FinishGame(game, _, _) if !game.aborted && indexFlowSetting.get() =>
      indexer(game).unit
  }
