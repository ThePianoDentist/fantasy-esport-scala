package v1.admin

import java.sql.Timestamp
import javax.inject.Inject

import entry.SquerylEntrypointForMyApp._
import play.api.libs.json._
import play.api.mvc._
import scala.concurrent.{ExecutionContext, Future}
import models._
import auth._
import v1.league.LeagueRepo

class AdminController @Inject()(cc: ControllerComponents, leagueRepo: LeagueRepo, auther: Auther)(implicit ec: ExecutionContext) extends AbstractController(cc)
  with play.api.i18n.I18nSupport{

  implicit val parser = parse.default

  def allProcessTransfersReq() = (new AuthAction() andThen auther.AdminCheckAction).async { implicit request =>
    Future {
      val currentTime = new Timestamp(System.currentTimeMillis())
      inTransaction {
        val updates = from(AppDB.leagueUserTable)(lu =>
              where(lu.changeTstamp.isNotNull and lu.changeTstamp <= currentTime)
              select(lu)
            ).map(processLeagueUserTransfer)
        Ok("Transfer updates processed")
      }
    }
  }

  def allRolloverPeriodReq() = (new AuthAction() andThen auther.AdminCheckAction).async { implicit request =>
    // // TODO test add leagues, sleep before end transaction, and see how id's turn out
    // Thread.sleep(2000)
    Future {
      val currentTime = new Timestamp(System.currentTimeMillis())
      inTransaction {
        leagueRepo.startPeriods(currentTime)
        leagueRepo.endPeriods(currentTime)
        Ok("Periods rolled over")
      }
    }
  }

  def addAPIUser() = (new AuthAction() andThen auther.AdminCheckAction).async { implicit request =>
    Future {
      inTransaction {
        Created(Json.toJson(AppDB.apiUserTable.insert(new APIUser("Testname", "test email", 1))))
      }
    }
  }

  private def processLeagueUserTransfer(leagueUser: LeagueUser) = {
    // TODO map and filter together
    println("in proc trans")
    val transfers = AppDB.transferTable.where(t => t.processed === false and t.leagueUserId === leagueUser.id)
    AppDB.teamPickeeTable.insert(transfers.filter(_.isBuy).map(t => new TeamPickee(t.pickeeId, t.leagueUserId)))
    AppDB.teamPickeeTable.deleteWhere(tp =>
      (tp.leagueUserId === leagueUser.id) and (tp.pickeeId in transfers.filter(!_.isBuy).map(_.pickeeId))
    )
    AppDB.transferTable.update(transfers.map(t => {
      t.processed = true; t
    }))
    leagueUser.changeTstamp = None
    AppDB.leagueUserTable.update(leagueUser)
  }

}
