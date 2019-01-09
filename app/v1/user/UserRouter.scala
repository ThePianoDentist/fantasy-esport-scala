package v1.user

import javax.inject.Inject

import play.api.routing.Router.Routes
import play.api.routing.SimpleRouter
import play.api.routing.sird._


class UserRouter @Inject()(controller: UserController) extends SimpleRouter {
  val prefix = "/v1/users"

  override def routes: Routes = {

    case POST(p"") =>
      controller.add

    case POST(p"/$id") =>
      controller.update(id)

    case GET(p"/$id") =>
      controller.show(id)

    case GET(p"/$id/leagues") =>
      controller.showAllLeagueUserReq(id)

    case PUT(p"/$userId/join/$leagueId") =>
      controller.joinLeague(userId, leagueId)
  }

}


