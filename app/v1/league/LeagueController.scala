package v1.league

import java.io.{PrintWriter, StringWriter}
import java.sql.Connection
import java.time.LocalDateTime

import javax.inject.Inject

import scala.concurrent.{ExecutionContext, Future}
import play.api.mvc._
import play.api.data.Form
import play.api.data.Forms._
import play.api.libs.json._
import play.api.data.format.Formats._
import play.api.db._
import utils.IdParser
import utils.TryHelper._
import auth.{UserAction, _}
import models._
import v1.user.{UserFormInput, UserRepo}
import v1.pickee.{PickeeFormInput, PickeeRepo}
import v1.transfer.TransferRepo

case class PeriodInput(
                        start: LocalDateTime, end: LocalDateTime, multiplier: Double, onStartCloseTransferWindow: Boolean,
                        onEndOpenTransferWindow: Boolean, onEndEliminateUsersTo: Option[Int]
                      )
case class UpdatePeriodInput(start: Option[LocalDateTime], end: Option[LocalDateTime], multiplier: Option[Double],
                             onStartCloseTransferWindow: Option[Boolean], onEndOpenTransferWindow: Option[Boolean],
                             onEndEliminateUsersTo: Option[Int]
                            )


case class LimitInput(name: String, max: Option[Int], benchMax: Option[Int])

case class LimitTypeInput(name: String, description: Option[String], max: Option[Int], benchMax: Option[Int], types: List[LimitInput])

case class TransferInput(
                          transferLimit: Option[Int], transferWildcard: Option[Boolean],
                          forceFullTeams: Boolean, noWildcardForLateRegister: Option[Boolean], system: String,
                          recycleValue: Option[BigDecimal], cardPackCost: Option[BigDecimal], cardPackSize: Option[Int],
                          predictionWinMoney: Option[BigDecimal], draftStart: Option[LocalDateTime], draftChoiceSecs: Option[Int],
                          //draftIsSnake: Boolean
                        )

case class FactionSpecificScoring(name: String, value: Double)
case class StatInput(
                      name: String, description: Option[String], allFactionPoints: Option[Double], noCardBonus: Boolean,
                      separateFactionPoints: List[FactionSpecificScoring]
                    )

case class LeagueFormInput(name: String, gameId: Option[Long], isPrivate: Boolean, tournamentId: Option[Long], periodDescription: String,
                           periods: List[PeriodInput], teamSize: Int, benchSize: Int, transferInfo: TransferInput, limits: List[LimitTypeInput],
                           startingMoney: BigDecimal, prizeDescription: Option[String], prizeEmail: Option[String],
                           stats: List[StatInput], manuallyCalculatePoints: Boolean,
                           pickeeDescription: String, pickees: List[PickeeFormInput], users: List[UserFormInput], apiKey: String,
                           applyPointsAtStartTime: Boolean, url: Option[String]
                          )


case class UpdateLeagueFormDraftInput(draftStart: Option[LocalDateTime], nextDraftDeadline: Option[LocalDateTime],
                                      choiceTimer: Option[Int], manualDraft: Option[Boolean],
                                      paused: Option[Boolean], order: Option[List[Long]]
                                     )
case class UpdateLeagueFormBasicInput(leagueName: Option[String], isPrivate: Option[Boolean],
                                      tournamentId: Option[Int],
                                      forceFullTeams: Option[Boolean],
                                      url: Option[String],
                                      periodDescription: Option[String],
                                      pickeeDescription: Option[String],
                                      applyPointsAtStartTime: Option[Boolean]
                                     )

case class UpdateLeagueFormTransferInput(transferOpen: Option[Boolean],
                                      transferLimit: Option[Int],
                                      transferWildcard: Option[Boolean],
                                      noWildcardForLateRegister: Option[Boolean],
                                     )

case class UpdateLeagueFormCardInput(recycleValue: Option[Double])

case class UpdateLeagueFormInput(
                                  league: Option[UpdateLeagueFormBasicInput], draft: Option[UpdateLeagueFormDraftInput],
                                  transfer: Option[UpdateLeagueFormTransferInput], card: Option[UpdateLeagueFormCardInput]
                                )

class LeagueController @Inject()(
                                  cc: ControllerComponents,
                                  userRepo: UserRepo, pickeeRepo: PickeeRepo,
                                  auther: Auther, transferRepo: TransferRepo
                                )(implicit ec: ExecutionContext, leagueRepo: LeagueRepo, db: Database) extends AbstractController(cc)
  with play.api.i18n.I18nSupport{  //https://www.playframework.com/documentation/2.6.x/ScalaForms#Passing-MessagesProvider-to-Form-Helpers

  private val form: Form[LeagueFormInput] = {

    Form(
      mapping(
        "name" -> nonEmptyText,
        "gameId" -> optional(of(longFormat)),
        "isPrivate" -> boolean,
        "tournamentId" -> optional(of(longFormat)),
        "periodDescription" -> nonEmptyText,
        "periods" -> list(mapping(
          "start" -> of(localDateTimeFormat("yyyy-MM-dd HH:mm")),
          "end" -> of(localDateTimeFormat("yyyy-MM-dd HH:mm")),
          "multiplier" -> default(of(doubleFormat), 1.0),
          "onStartCloseTransferWindow" -> default(boolean, false),
          "onEndOpenTransferWindow" -> default(boolean, false),
          "onEndEliminateUsersTo" -> optional(number(min=0)),
        )(PeriodInput.apply)(PeriodInput.unapply)),
        "teamSize" -> default(number(min=1, max=20), 5),
        "benchSize" -> default(number(min=0, max=20), 0),
        //"captain" -> default(boolean, false),
        "transferInfo" -> mapping(
          "transferLimit" -> optional(number),
          "transferWildcard" -> optional(boolean),
          "forceFullTeams" -> default(boolean, false),
          "noWildcardForLateRegister" -> optional(boolean),
          "system" -> nonEmptyText,  // TODO constrain types
          "recycleValue" -> optional(bigDecimal(10, 1)),
          "cardPackCost" -> optional(bigDecimal(10, 1)),
          "cardPackSize" -> optional(number),
          "predictionWinMoney" -> optional(bigDecimal(10, 1)),
          "draftStart" -> optional(of(localDateTimeFormat)),
          "draftChoiceSecs" -> optional(number)
        )(TransferInput.apply)(TransferInput.unapply),
        "limits" -> list(mapping(
          "name" -> nonEmptyText,
          "description" -> optional(nonEmptyText),
          "max" -> optional(number),
          "benchMax" -> optional(number),
          "types" -> list(mapping(
            "name" -> nonEmptyText,
            "max" -> optional(number),
            "benchMax" -> optional(number),
          )(LimitInput.apply)(LimitInput.unapply))
        )(LimitTypeInput.apply)(LimitTypeInput.unapply)),
        "startingMoney" -> default(bigDecimal(10, 1), BigDecimal.decimal(50.0)),
    // also singular prize with description and email fields
        "prizeDescription" -> optional(nonEmptyText),
        "prizeEmail" -> optional(nonEmptyText),
        // dont need a list of limits as input, as we just take them from their entry in pickee list
        "stats" -> list(mapping(
          // TODO find how to put dynamic mappings into play input
          "name" -> nonEmptyText,
          "description" -> optional(nonEmptyText),
          "allFactionPoints" -> optional(of(doubleFormat)),
          "noCardBonus" -> default(boolean, value=false),
          "separateFactionPoints" -> list(mapping(
            "name" -> nonEmptyText,
            "value" -> of(doubleFormat)
          )(FactionSpecificScoring.apply)(FactionSpecificScoring.unapply))
        )(StatInput.apply)(StatInput.unapply)),
        "manuallyCalculatePoints" -> default(boolean, false),
        "pickeeDescription" -> nonEmptyText, //i.e. Hero for dota, Champion for lol, player for regular fantasy styles
        "pickees" -> list(mapping(
          "id" -> of(longFormat),
          "name" -> nonEmptyText,
          "value" -> bigDecimal(10, 1),
          "active" -> default(boolean, true),
          "limits" -> list(nonEmptyText)
        )(PickeeFormInput.apply)(PickeeFormInput.unapply)),
        "users" -> list(mapping(
          "username" -> nonEmptyText,
          "userId" -> of(longFormat)
        )(UserFormInput.apply)(UserFormInput.unapply)),
        "apiKey" -> nonEmptyText,
        "applyPointsAtStartTime" -> default(boolean, true),
        "url" -> optional(nonEmptyText),
      )(LeagueFormInput.apply)(LeagueFormInput.unapply)
    )
  }

  private val updateForm: Form[UpdateLeagueFormInput] = {
    Form(
      mapping(
        "league" -> optional(mapping(
          "leagueName" -> optional(nonEmptyText),
          "isPrivate" -> optional(boolean),
          "tournamentId" -> optional(number),
          "forceFullTeams" -> optional(boolean),
          "url" -> optional(nonEmptyText),
          "periodDescription" -> optional(nonEmptyText),
          "pickeeDescription" -> optional(nonEmptyText),
          "applyPointsAtStartTime" -> optional(boolean)
        )(UpdateLeagueFormBasicInput.apply)(UpdateLeagueFormBasicInput.unapply)),
        "draft" -> optional(mapping(
          "draftStart" -> optional(of(localDateTimeFormat("yyyy-MM-dd HH:mm:ss"))),
        "nextDraftDeadline" -> optional(of(localDateTimeFormat("yyyy-MM-dd HH:mm:ss"))),
          "choiceTimer" -> optional(number),
          "manualDraft" -> optional(boolean),
          "paused" -> optional(boolean),
          "order" -> optional(list(of(longFormat)))
        )(UpdateLeagueFormDraftInput.apply)(UpdateLeagueFormDraftInput.unapply)),
        "transfer" -> optional(mapping(
          "transferOpen" -> optional(boolean),
          "transferLimit" -> optional(number),
          "transferWildcard" -> optional(boolean),
          "noWildcardForLateRegister" -> optional(boolean)
        )(UpdateLeagueFormTransferInput.apply)(UpdateLeagueFormTransferInput.unapply)),
        "card" -> optional(mapping(
          "recycleValue" -> optional(of(doubleFormat))
        )(UpdateLeagueFormCardInput.apply)(UpdateLeagueFormCardInput.unapply))
      )(UpdateLeagueFormInput.apply)(UpdateLeagueFormInput.unapply)
    )
  }

  private val updatePeriodForm: Form[UpdatePeriodInput] = {
    Form(
      mapping(
        "start" -> optional(of(localDateTimeFormat("yyyy-MM-dd HH:mm"))),
        "end" -> optional(of(localDateTimeFormat("yyyy-MM-dd HH:mm"))),
        "multiplier" -> optional(of(doubleFormat)),
        "onStartCloseTransferWindow" -> optional(boolean),
        "onEndOpenTransferWindow" -> optional(boolean),
        "onEndEliminateUsersTo" -> optional(number(min=0))
      )(UpdatePeriodInput.apply)(UpdatePeriodInput.unapply)
    )
  }

  implicit val parser = parse.default

  def get(leagueId: String) = (new LeagueAction(leagueId)).async { implicit request =>
    Future(Ok(Json.toJson(request.league)))
  }

  def getWithRelatedReq(leagueId: String) = (new LeagueAction(leagueId)).async { implicit request =>
    Future(db.withConnection { implicit c => Ok(Json.toJson(
      leagueRepo.getWithRelated(
        request.league.leagueId, request.getQueryString("periods").isDefined, request.getQueryString("scoring").isDefined,
        request.getQueryString("statfields").isDefined, request.getQueryString("limits").isDefined
      )
    ))})
  }

  def update(leagueId: String) = (new AuthAction() andThen auther.AuthLeagueAction(leagueId) andThen auther.PermissionCheckAction).async { implicit request =>
    db.withConnection { implicit c => processJsonUpdateLeague(request.league)}
  }

  def add = Action.async(parse.json){implicit request => processJsonLeague()}

  def getAllUsersReq(leagueId: String) = (new LeagueAction(leagueId)).async { implicit request =>
    Future {
        db.withConnection { implicit c =>
          val users = userRepo.getAllUsersForLeague(request.league.leagueId)
          Ok(Json.toJson(users))
        }
    }
  }

  def showUserReq(userId: String, leagueId: String) = (new LeagueAction(leagueId)
    andThen new UserAction(userRepo, db)(userId).apply()).async { implicit request =>
        Future(Ok(Json.toJson(request.user)))
  }

  def getRankingsReq(leagueId: String, statFieldName: String) = (new LeagueAction(leagueId)).async { implicit request =>
    Future {
      db.withConnection { implicit c =>
        val users = request.getQueryString("users").map(_.split(",").map(_.toLong))
        val showTeam = request.getQueryString("team").isDefined
        (for {
          secondaryOrdering <- tryOrResponse(request.getQueryString("secondary").map(_.split(",").
            toList.map(s => leagueRepo.getStatFieldId(request.league.leagueId, s).get)), BadRequest("Unknown secondary stat field"))
          statFieldId <- leagueRepo.getStatFieldId(request.league.leagueId, statFieldName).toRight(BadRequest("Unknown stat field"))
          period <- tryOrResponse[Option[Int]](request.getQueryString("period").map(_.toInt), BadRequest("Invalid period format"))
          rankings = userRepo.getRankings(request.league, statFieldId, period, users, secondaryOrdering, showTeam)
        } yield Ok(Json.toJson(rankings))).fold(identity, identity)
      }
    }
  }

  def endPeriodReq(leagueId: String) = (new AuthAction() andThen auther.AuthLeagueAction(leagueId) andThen auther.PermissionCheckAction).async {implicit request =>
    Future {
      db.withTransaction { implicit c =>
        implicit val processWaiverPickeesFunc: (Long, Int, Int) => Unit = transferRepo.processWaiverPickees
        leagueRepo.getCurrentPeriod(request.league) match {
          case Some(p) if !p.ended => {
            tryOrResponseRollback({
              leagueRepo.postEndPeriodHook(List(
                PostPeriodHookRow(request.league.leagueId, p.periodId, request.league.teamSize)
              ), LocalDateTime.now())
            }, c, InternalServerError("We dun goofed")).fold(identity, x => Ok("Successfully ended day"))
          }
          case _ => BadRequest("Period already ended (Must start next period first)")
        }
      }
    }
  }

  def startPeriodReq(leagueId: String) = (new AuthAction() andThen auther.AuthLeagueAction(leagueId) andThen auther.PermissionCheckAction).async {implicit request =>
    Future {
      // hacky way to avoid circular dependency
      db.withConnection { implicit c =>
        (for {
          newPeriod <- leagueRepo.getNextPeriod(request.league, requireCurrentPeriodEnded = true)
          _ = leagueRepo.postStartPeriodHook(request.league.leagueId, newPeriod.periodId, newPeriod.value, LocalDateTime.now())
          out = Ok(f"Successfully started period $newPeriod")
        } yield out).fold(identity, identity)
      }
    }
  }

  def updatePeriodReq(leagueId: String, periodValue: String) = (new AuthAction() andThen auther.AuthLeagueAction(leagueId) andThen auther.PermissionCheckAction).async { implicit request =>
    Future {
      db.withConnection { implicit c =>
        (for {
          periodValueInt <- IdParser.parseIntId(Some(periodValue), "period value", required=true)
          out = handleUpdatePeriodForm(request.league.leagueId, periodValueInt.get)
        } yield out).fold(identity, identity)
      }
    }
  }

  def generateDraftOrderReq(leagueId: String) = (new AuthAction() andThen auther.AuthLeagueAction(leagueId)
    andThen auther.PermissionCheckAction).async { implicit request =>
    Future {
      db.withConnection { implicit c =>
        Ok(Json.toJson(leagueRepo.generateDraftOrder(request.league.leagueId, request.league.teamSize + request.league.benchSize)))
      }
    }
  }

  private def handleUpdatePeriodForm[A](leagueId: Long, periodValue: Int)(implicit request: Request[A]): Result = {
    def failure(badForm: Form[UpdatePeriodInput]) = {
      BadRequest(badForm.errorsAsJson)
    }

    def success(input: UpdatePeriodInput) = {
      db.withConnection { implicit c =>
        (for {
          updatedPeriodId <- tryOrResponse(leagueRepo.updatePeriod(
            leagueId, periodValue, input.start, input.end, input.multiplier, input.onStartCloseTransferWindow,
            input.onEndOpenTransferWindow, input.onEndEliminateUsersTo
          ), BadRequest("Invalid leagueId or period value"))
          out = Ok(Json.toJson("Successfully updated"))
        } yield out).fold(identity, identity)
      }
    }
    updatePeriodForm.bindFromRequest().fold(failure, success)
  }

  private def processJsonLeague[A]()(implicit request: Request[A]): Future[Result] = {
    def failure(badForm: Form[LeagueFormInput]) = {
      Future.successful(BadRequest(badForm.errorsAsJson))
    }

    def extraInputValidation(input: LeagueFormInput) = Right(true)

    def success(input: LeagueFormInput): Future[Result] = {
      Future{
        db.withTransaction { implicit c =>
          try {
            val validation = extraInputValidation(input)
            if (validation.isLeft) validation.left.get else {
              val newLeague = leagueRepo.insert(input)
              (input.prizeDescription, input.prizeEmail) match {
                case (Some(d), Some(e)) => leagueRepo.insertLeaguePrize(newLeague.leagueId, d, e)
                case (Some(d), None) => return Future.successful(BadRequest("Must enter prize email with description"))
                case (None, Some(e)) => return Future.successful(BadRequest("Must enter prize description with email"))
                case _ => ;
              }
              println(newLeague)
              println(newLeague.leagueId)
              val limitNamesToIds = leagueRepo.insertLimits(newLeague.leagueId, input.limits)
              val newPickeeIds = input.pickees.map(pickeeRepo.insertPickee(newLeague.leagueId, _))
              pickeeRepo.insertPickeeLimits(input.pickees, newPickeeIds, limitNamesToIds)

              val pointsFieldId = leagueRepo.insertStatField(newLeague.leagueId, "points", None)
              val statFieldIds = List(pointsFieldId) ++ input.stats.map({
                es => {
                  val statFieldId = leagueRepo.insertStatField(newLeague.leagueId, es.name, es.description)
                  if (!input.manuallyCalculatePoints) {
                    if (es.allFactionPoints.isDefined) {
                      leagueRepo.insertScoringField(statFieldId, Option.empty[Long], es.allFactionPoints.get, es.noCardBonus)
                    }
                    else {
                      es.separateFactionPoints.foreach(
                        sfp => leagueRepo.insertScoringField(statFieldId, Some(limitNamesToIds(sfp.name)), sfp.value, es.noCardBonus)
                      )
                    }
                  }
                  statFieldId
                }
              })

              val newUsers = input.users.map(u => userRepo.insertUser(newLeague, u.userId, u.username))
              val newPickeeStatIds = statFieldIds.flatMap(sf => newPickeeIds.map(np => pickeeRepo.insertPickeeStat(sf, np)))
              val newUserStatIds = statFieldIds.flatMap(sf => newUsers.map(nlu => userRepo.insertUserStat(sf, nlu.userId)))

              newPickeeStatIds.foreach(npId => pickeeRepo.insertPickeeStatDaily(npId, Option.empty[Int]))
              newUserStatIds.foreach(nluid => userRepo.insertUserStatDaily(nluid, None))
              var nextPeriodId: Option[Long] = None
              // have to be inserted 'back to front', so that we can know and specify id of nextPeriod, and link them.
              input.periods.zipWithIndex.reverse.foreach({ case (p, i) => {
                val newPeriodId = leagueRepo.insertPeriod(newLeague.leagueId, p, i + 1, nextPeriodId)
                nextPeriodId = Some(newPeriodId)
                newPickeeStatIds.foreach(npId => pickeeRepo.insertPickeeStatDaily(npId, Some(i + 1)))
                newUserStatIds.foreach(nluid => userRepo.insertUserStatDaily(nluid, Some(i + 1)))
              }
              })

              Created(Json.toJson(newLeague))
            }
          } catch {case e: Throwable => {        val sw = new StringWriter
            e.printStackTrace(new PrintWriter(sw))
            println(sw.toString)}; c.rollback(); InternalServerError("Something went wrong")}
        }
      }
    }

    form.bindFromRequest().fold(failure, success)
  }

  private def processJsonUpdateLeague[A](league: LeagueRow)(implicit request: Request[A]): Future[Result] = {
    def failure(badForm: Form[UpdateLeagueFormInput]) = {
      Future.successful(BadRequest(badForm.errorsAsJson))
    }

    def success(input: UpdateLeagueFormInput) = Future(
      if (leagueRepo.isStarted(league) &&
        (input.transfer.flatMap(_.transferLimit).isDefined || input.transfer.flatMap(_.transferWildcard).isDefined)){
        BadRequest("Cannot update transfer limits or wildcard after league has started")
      } else{
        db.withConnection { implicit c =>
          Ok(Json.toJson(leagueRepo.update(league.leagueId, input)))
        }
      }
    )

    updateForm.bindFromRequest().fold(failure, success)
  }
}
