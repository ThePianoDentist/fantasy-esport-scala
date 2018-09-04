package v1.transfer

import java.sql.Timestamp
import javax.inject.Inject

import entry.SquerylEntrypointForMyApp._
import play.api.mvc._
import play.api.data.Form
import play.api.data.Forms._
import play.api.libs.json._
import scala.concurrent.{ExecutionContext, Future}
import scala.collection.immutable.{List, Set}
import scala.util.Try
import models.{AppDB, LeagueUser, Pickee}
import utils.IdParser

case class TransferFormInput(buy: List[Int], sell: List[Int], isCheck: Boolean)

class TransferController @Inject()(cc: ControllerComponents)(implicit ec: ExecutionContext) extends AbstractController(cc)
  with play.api.i18n.I18nSupport{  //https://www.playframework.com/documentation/2.6.x/ScalaForms#Passing-MessagesProvider-to-Form-Helpers

  private val transferForm: Form[TransferFormInput] = {

    Form(
    mapping(
    "buy" -> default(list(number), List()),
    "sell" -> default(list(number), List()),
    "isCheck" -> boolean
    )(TransferFormInput.apply)(TransferFormInput.unapply)
    )
  }

  // todo add a transfer check call
  def transfer(userId: String, leagueId: String) = Action.async(parse.json) { implicit request =>
    processJsonTransfer(userId, leagueId)
  }

  private def processJsonTransfer[A](userId: String, leagueId: String)(implicit request: Request[A]): Future[Result] = {
    def failure(badForm: Form[TransferFormInput]) = {
      Future.successful(BadRequest(badForm.errorsAsJson))
    }

    def success(input: TransferFormInput) = {
      println("yay")
      // verify leagueUser exists
      // verify doesnt violate remaining transfers
      // verify can afford change
      // verify doesnt break team size lim
      // verify doesnt break faction limit

      // stop people from buying two of same hero at once
      // still need further check that hero not already in team
      val sell = input.sell.toSet
      val buy = input.buy.toSet

      Future {
        inTransaction {
          // TODO handle invalid Id
          (for {
            userId <- IdParser.parseIntId(userId, "User")
            leagueId <- IdParser.parseIntId(leagueId, "League")
            league <- AppDB.leagueTable.lookup(leagueId).toRight(BadRequest(f"League does not exist: $leagueId"))
          // TODO what does single return if no entries?
            leagueUser <- Try(league.users.associations.where(lu => lu.id === userId).single).toOption.toRight(BadRequest(f"User($userId) not in this league($leagueId)"))
            newRemaining <- updatedRemainingTransfers(leagueUser, sell)
            isValidPickees <- validatePickeeIds(league.pickees, sell, buy)
            newMoney <- updatedMoney(leagueUser, league.pickees, sell, buy)
            finished = Ok("Transfers successful")
          } yield finished).fold(identity, identity)
        }
      }
      //scala.concurrent.Future{ Ok(views.html.index())}
      //      postResourceHandler.create(input).map { post =>
      //      Created(Json.toJson(post)).withHeaders(LOCATION -> post.link)
      //      }
      // TODO good practice post-redirect-get
    }

    transferForm.bindFromRequest().fold(failure, success)
  }

  private def updatedRemainingTransfers(leagueUser: LeagueUser, toSell: Set[Int]): Either[Result, Int] = {
    val newRemaining = leagueUser.remainingTransfers - toSell.size
    newRemaining match{
      case x if x < 0 => Left(BadRequest(
        f"Insufficient remaining transfers: $leagueUser.remainingTransfers"
      ))
      case x => Right(x)
    }
  }

  private def validatePickeeIds(pickees: Iterable[Pickee], toSell: Set[Int], toBuy: Set[Int]): Either[Result, Boolean] = {
    // TODO return what ids are invalid
    (toSell ++ toBuy).subsetOf(pickees.map(_.identifier).toSet) match {
      case true => Right(true)
      case false => Left(BadRequest(
        "Invalid pickee id used"
      ))
    }
  }

  private def updatedMoney(leagueUser: LeagueUser, pickees: Iterable[Pickee], toSell: Set[Int], toBuy: Set[Int]): Either[Result, BigDecimal] = {
    val updated = leagueUser.money + pickees.filter(p => toSell.contains(p.identifier)).map(_.value).sum -
      pickees.filter(p => toBuy.contains(p.identifier)).map(_.value).sum
    println(updated)
    updated match {
      case x if x >= 0.0 => Right(x)
      case x => Left(BadRequest(
        f"Insufficient credits. Transfers would leave user at $x credits"
      ))
    }
  }
}