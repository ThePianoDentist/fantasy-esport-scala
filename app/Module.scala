import javax.inject._

import com.google.inject.AbstractModule
import net.codingwell.scalaguice.ScalaModule
import play.api.{Configuration, Environment}
import v1.league.{LeagueRepo, LeagueRepoImpl}
import v1.pickee.{PickeeRepo, PickeeRepoImpl}
import v1.result.{ResultRepo, ResultRepoImpl}
import v1.team.{TeamRepo, TeamRepoImpl}
import v1.transfer.{TransferRepo, TransferRepoImpl}
import v1.admin.{AdminRepo, AdminRepoImpl}
import v1.user.{UserRepo, UserRepoImpl}


/**
  * Sets up custom components for Play.
  *
  * https://www.playframework.com/documentation/latest/ScalaDependencyInjection
  */
class Module(environment: Environment, configuration: Configuration)
    extends AbstractModule
    with ScalaModule {

  override def configure() = {
    println("configure called")
    bind[LeagueRepo].to[LeagueRepoImpl].in[Singleton]
    bind[PickeeRepo].to[PickeeRepoImpl].in[Singleton]
    bind[ResultRepo].to[ResultRepoImpl].in[Singleton]
    bind[TeamRepo].to[TeamRepoImpl].in[Singleton]
    bind[TransferRepo].to[TransferRepoImpl].in[Singleton]
    bind[UserRepo].to[UserRepoImpl].in[Singleton]
    bind[AdminRepo].to[AdminRepoImpl].in[Singleton]
  }
}