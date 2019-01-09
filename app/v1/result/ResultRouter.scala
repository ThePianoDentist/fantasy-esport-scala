package v1.result

import javax.inject.Inject

import play.api.routing.Router.Routes
import play.api.routing.SimpleRouter
import play.api.routing.sird._


class ResultRouter @Inject()(controller: ResultController) extends SimpleRouter {
  val prefix = "/v1/results"

  override def routes: Routes = {

    case POST(p"/$leagueId") =>
      controller.add(leagueId)

    case GET(p"/leagues/$leagueId") =>
      controller.getReq(leagueId)

//    case GET(p"/$id") =>
//      controller.show(id)
  }

}


