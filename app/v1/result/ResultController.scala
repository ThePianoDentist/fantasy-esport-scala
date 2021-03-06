package v1.result

import java.sql.Connection
import java.time.LocalDateTime

import javax.inject.Inject

import scala.concurrent.{ExecutionContext, Future}
import play.api.mvc._
import play.api.data.Form
import play.api.data.Forms._
import play.api.libs.json._
import play.api.data.format.Formats._
import models._
import anorm._
import anorm.{Macro, RowParser}
import Macro.ColumnNaming
import play.api.db._
import utils.TryHelper.{tryOrResponse, tryOrResponseRollback}
import auth._
import play.api.Logger
import v1.league.LeagueRepo
import v1.pickee.PickeeRepo
import v1.user.UserRepo


case class MatchFormInput(
                         matchId: Long, matchTeamOneFinalScore: Option[Int],
                         matchTeamTwoFinalScore: Option[Int], startTstamp: Option[LocalDateTime],
                         targetAtTstamp: Option[LocalDateTime],
                         pickeeResults: List[PickeeFormInput]
                         )

case class SeriesFormInput(
                            seriesId: Long, tournamentId: Option[Long], teamOne: Option[String], teamTwo: Option[String],
                            bestOf: Int, seriesTeamOneCurrentScore: Int, seriesTeamTwoCurrentScore: Int,
                            seriesTeamOneFinalScore: Option[Int], seriesTeamTwoFinalScore: Option[Int],
                            startTstamp: Option[LocalDateTime], matches: List[MatchFormInput]
                          )

case class ResultFormInput(
                            matchId: Long, tournamentId: Option[Long], teamOne: Option[String], teamTwo: Option[String],
                            teamOneScore: Int, teamTwoScore: Int,
                            startTstamp: Option[LocalDateTime], targetAtTstamp: Option[LocalDateTime], pickees: List[PickeeFormInput]
                          )

case class PickeeFormInput(id: Long, isTeamOne: Boolean, stats: List[StatsFormInput])

case class InternalPickee(id: Long, isTeamOne: Boolean, stats: List[StatsFormInput])

case class StatsFormInput(field: String, value: Double)

case class FixtureFormInput(matchId: Long, tournamentId: Option[Long], teamOne: String, teamTwo: String, startTstamp: LocalDateTime)

case class FindByTeamsFormInput(teamOne: String, teamTwo: String, includeReversedTeams: Boolean)

case class PredictionFormInput(seriesId: Option[Long], matchId: Option[Long], teamOneScore: Int, teamTwoScore: Int)
case class PredictionsFormInput(predictions: List[PredictionFormInput])

case class InternalIdMaybeChildId(internalId: Long, childId: Option[Long], startTstamp: LocalDateTime)

class ResultController @Inject()(cc: ControllerComponents, userRepo: UserRepo, resultRepo: ResultRepo, pickeeRepo: PickeeRepo, Auther: Auther)
                                (implicit ec: ExecutionContext, leagueRepo: LeagueRepo, db: Database) extends AbstractController(cc)
  with play.api.i18n.I18nSupport{  //https://www.playframework.com/documentation/2.6.x/ScalaForms#Passing-MessagesProvider-to-Form-Helpers
  private val logger = Logger("application")
  private val seriesForm: Form[SeriesFormInput] = {
    Form(
      mapping(
        "seriesId" -> of(longFormat),
        "tournamentId" -> optional(of(longFormat)),
        "teamOne" -> optional(nonEmptyText),
        "teamTwo" -> optional(nonEmptyText),
        "bestOf" -> default(number, 1),
        "seriesTeamOneCurrentScore" -> default(number, 0),
        "seriesTeamTwoCurrentScore" -> default(number, 0),
        "seriesTeamOneFinalScore" -> optional(number),
        "seriesTeamTwoFinalScore" -> optional(number),
        "startTstamp" -> optional(of(localDateTimeFormat("yyyy-MM-dd HH:mm:ss"))),
        "matches" -> list(mapping(
          "matchId" -> of(longFormat),
          "matchTeamOneFinalScore" -> optional(number),
          "matchTeamTwoFinalScore" -> optional(number),
          "startTstamp" -> optional(of(localDateTimeFormat("yyyy-MM-dd HH:mm:ss"))),
          "targetAtTstamp" -> optional(of(localDateTimeFormat("yyyy-MM-dd HH:mm:ss"))),
          "pickeeResults" -> list(mapping(
            "id" -> of(longFormat),
            "isTeamOne" -> boolean,
            "stats" -> list(mapping(
              "field" -> nonEmptyText,
              "value" -> of(doubleFormat)
            )(StatsFormInput.apply)(StatsFormInput.unapply))
          )(PickeeFormInput.apply)(PickeeFormInput.unapply))
        )(MatchFormInput.apply)(MatchFormInput.unapply))
      )(SeriesFormInput.apply)(SeriesFormInput.unapply))
  }

  private val predictionForm: Form[PredictionFormInput] = {
    Form(mapping(
      "seriesId" -> optional(of(longFormat)),
      "matchId" -> optional(of(longFormat)),
      "teamOneScore" -> of(intFormat),
      "teamTwoScore" -> of(intFormat)
    )(PredictionFormInput.apply)(PredictionFormInput.unapply))
  }
  private val predictionsForm: Form[PredictionsFormInput] = {
    Form(mapping("predictions" -> list(mapping(
      "seriesId" -> optional(of(longFormat)),
      "matchId" -> optional(of(longFormat)),
      "teamOneScore" -> of(intFormat),
      "teamTwoScore" -> of(intFormat)
    )(PredictionFormInput.apply)(PredictionFormInput.unapply)))(PredictionsFormInput.apply)(PredictionsFormInput.unapply))
  }

  private val findByTeamsForm: Form[FindByTeamsFormInput] = {
    Form(mapping(
      "teamOne" -> nonEmptyText,
      "teamTwo" -> nonEmptyText,
      "includeReversedTeams" -> default(boolean, false)
    )(FindByTeamsFormInput.apply)(FindByTeamsFormInput.unapply))
  }

  implicit val parser = parse.default

  def add(leagueId: String) = (new AuthAction() andThen Auther.AuthLeagueAction(leagueId) andThen Auther.PermissionCheckAction).async{ implicit request =>
    db.withConnection { implicit c => processSeriesInput(request.league)}
  }

  private def processSeriesInput[A](league: LeagueRow)(implicit request: Request[A]): Future[Result] = {
    def failure(badForm: Form[SeriesFormInput]) = {
      Future.successful(BadRequest(badForm.errorsAsJson))
    }

    def success(input: SeriesFormInput) = {
      Future {
        val now = LocalDateTime.now
        //var error: Option[Result] = None
        db.withTransaction { implicit c =>
          val existingSeries = existingSeriesInfo(league.leagueId, input.seriesId)
          val startTstamp = input.startTstamp.orElse(existingSeries.map(_._2)).getOrElse(now)
          (for {
            correctPeriod <- getPeriod(startTstamp, Some(startTstamp), league, now)
            seriesId <- if (existingSeries.isDefined) Right(existingSeries.get._1) else
              newSeries(input, league, correctPeriod, startTstamp)
            _ <- if (existingSeries.isDefined) updatedSeries(seriesId, input) else Right(true)
            _ = if (input.seriesTeamOneFinalScore.isDefined)
              awardPredictions(Some(seriesId), None, input.seriesTeamOneFinalScore.get, input.seriesTeamTwoFinalScore.get)
            // TODO ideally would bail on first error
            _ <- input.matches.map(matchu => for {
              existingMatch <- existingMatchInfo(league.leagueId, matchu.matchId)
              startTstamp = matchu.startTstamp.orElse(existingMatch.map(_._2))
              targetTstamp = getTargetedAtTstamp(matchu.targetAtTstamp, startTstamp, league, now)
              matchId <- if (existingMatch.isDefined) Right(existingMatch.get._1) else
                newMatch(matchu, seriesId, correctPeriod, targetTstamp, now)
              internalPickee = matchu.pickeeResults.map(
                p => InternalPickee(pickeeRepo.getInternalId(league.leagueId, p.id).get, p.isTeamOne, p.stats)
              )
              _ <- if (existingMatch.isDefined) updatedMatch(matchId, matchu) else Right(true)
              _ = if (matchu.matchTeamOneFinalScore.isDefined)
                awardPredictions(None, Some(matchId), matchu.matchTeamOneFinalScore.get, matchu.matchTeamTwoFinalScore.get)
              insertedResults <- newResults(matchu, league, matchId, internalPickee)
              insertedStats <- newStats(league, insertedResults.toList, internalPickee)
              _ <- updateStats(insertedStats, league, correctPeriod, targetTstamp)
            } yield "Added match").foldRight(Right(Nil): Either[Result, List[Any]]) { (elem, acc) =>
              acc.right.flatMap(list => elem.right.map(_ :: list))}
          } yield Created("Successfully added results")).fold(identity, identity)
        }
      }
    }

    seriesForm.bindFromRequest().fold(failure, success)
  }

  private def existingMatchInfo(leagueId: Long, externalMatchId: Long)(implicit c: Connection): Either[Result, Option[(Long, LocalDateTime)]] = {
    val parser: RowParser[InternalIdMaybeChildId] = Macro.namedParser[InternalIdMaybeChildId](ColumnNaming.SnakeCase)
    val existing = SQL"""
         select m.match_id as internal_id, result_id as child_id, m.start_tstamp from matchu m
         join series s using(series_id)
         left join resultu using(match_id) where external_match_id = $externalMatchId and league_id = $leagueId
          LIMIT 1
       """.as(parser.singleOpt)
    existing match{
      case Some(x) if x.childId.isDefined => Left(BadRequest("Cannot add results for same match twice"))
      case Some(x) => Right(Some(x.internalId, x.startTstamp))
      case None => Right(None)
    }
  }

  private def existingSeriesInfo(leagueId: Long, externalSeriesId: Long)(implicit c: Connection): Option[(Long, LocalDateTime)] = {
    val parser: RowParser[InternalIdMaybeChildId] = Macro.namedParser[InternalIdMaybeChildId](ColumnNaming.SnakeCase)
    // TODO some junk here
    SQL"""
         select series_id as internal_id, match_id as child_id, s.start_tstamp from series s
         left join matchu using(series_id) where external_series_id = $externalSeriesId and league_id = $leagueId
          LIMIT 1
       """.as(parser.singleOpt).map(x => (x.internalId, x.startTstamp))
  }

  private def updatedMatch(matchId: Long, input: MatchFormInput)(implicit c: Connection): Either[Result, Any] = {
    if (input.matchTeamOneFinalScore.isDefined) {
      val updatedCount = SQL"""update matchu set match_team_one_final_score = ${input.matchTeamOneFinalScore}, match_team_two_final_score = ${input.matchTeamTwoFinalScore}
         where match_id = $matchId and match_team_one_final_score is null
       """.executeUpdate()
      if (updatedCount == 0) return Left(BadRequest("Cannot update final match score after setting"))
    }
    Right(true)
  }

  private def updatedSeries(seriesId: Long, input: SeriesFormInput)(implicit c: Connection): Either[Result, Any] = {
    // TODO this forces you to always send through current score, or it will set back to 0-0
    val updatedCount = SQL"""update series set series_team_one_final_score = ${input.seriesTeamOneFinalScore},
                        series_team_two_final_score = ${input.seriesTeamTwoFinalScore},
                        series_team_one_current_score = ${input.seriesTeamOneCurrentScore},
                        series_team_two_current_score = ${input.seriesTeamTwoCurrentScore}
       where series_id = $seriesId and series_team_one_final_score is null
     """.executeUpdate()
    if (updatedCount == 0) return Left(BadRequest("Cannot update final series score after setting"))
    Right(true)
  }

  private def awardPredictions(seriesId: Option[Long], matchId: Option[Long], teamOneScore: Int, teamTwoScore: Int)(implicit c: Connection) = {
    val sql = if (matchId.isDefined) SQL"""select user_id, prediction_id from prediction_match where match_id = $matchId
                         AND team_one_score = $teamOneScore AND team_two_score = $teamTwoScore
         AND paid_out = false FOR UPDATE"""
    else SQL"""select user_id, prediction_id from prediction_series where series_id = $seriesId
                         AND team_one_score = $teamOneScore AND team_two_score = $teamTwoScore
         AND paid_out = false FOR UPDATE"""
       val winningUsers = sql.as((SqlParser.long("user_id") ~ SqlParser.long("prediction_id")).*)
    winningUsers.map({case userId ~ predictionId =>
      SQL"""update useru u set money = money + prediction_win_money from league l
           where u.league_id = l.league_id AND u.user_id = $userId""".executeUpdate()
      SQL"""update prediction set paid_out = true where prediction_id = $predictionId""".executeUpdate()
    })
  }

  private def getPeriod(startTstamp: LocalDateTime, targetAtTstamp: Option[LocalDateTime], league: LeagueRow, now: LocalDateTime)(implicit c: Connection): Either[Result, Int] = {
    println(league.applyPointsAtStartTime)
    println(now)

    val targetedAtTstamp = (targetAtTstamp, league.applyPointsAtStartTime) match {
      case (Some(x), _) => x
      case (_, true) => startTstamp
      case _ => now
    }
    logger.info("oOOooooooOOOOoooOOOooo")
    logger.info(targetedAtTstamp.toString)
    tryOrResponse(
      {val out = leagueRepo.getPeriodFromTimestamp(league.leagueId, targetedAtTstamp).get.value
        logger.info(s"Adding results for period $out")
        out
      },
      InternalServerError("Cannot add result outside of period")
    )
  }

  private def getTargetedAtTstamp(targetAtTstamp: Option[LocalDateTime], startTstamp: Option[LocalDateTime], league: LeagueRow, now: LocalDateTime): LocalDateTime = {
    (targetAtTstamp, league.applyPointsAtStartTime) match {
      case (Some(x), _) => x
      case (_, true) => startTstamp.getOrElse(now)  // TODO check this getOrElse
      case _ => now
    }
  }

  private def newMatch(input: MatchFormInput, seriesId: Long, period: Int, targetAt: LocalDateTime, now: LocalDateTime)
                      (implicit c: Connection): Either[Result, Long] = {
    tryOrResponse[Long](resultRepo.insertMatch(
      seriesId, period, input, now, targetAt
    ), InternalServerError("Internal server error adding match"))
  }

  private def newSeries(input: SeriesFormInput, league: LeagueRow, period: Int, startTstamp: LocalDateTime)
                      (implicit c: Connection): Either[Result, Long] = {
    tryOrResponse[Long](resultRepo.insertSeries(
      league.leagueId, period, input, startTstamp
    ), InternalServerError("Internal server error adding match"))
  }

  private def newResults(input: MatchFormInput, league: LeagueRow, matchId: Long, pickees: List[InternalPickee])
                        (implicit c: Connection): Either[Result, Iterable[Long]] = {
    tryOrResponseRollback(pickees.map(p => resultRepo.insertResult(matchId, p)), c, InternalServerError("Internal server error adding result"))
  }

  private def newStats(league: LeagueRow, resultIds: List[Long], pickees: List[InternalPickee])
                      (implicit c: Connection): Either[Result, List[(StatsRow, Long)]] = {
    tryOrResponseRollback({
      // TODO tidy same code in branches
      if (league.manuallyCalculatePoints) {
        pickees.zip(resultIds).flatMap({ case (ip, resultId) => ip.stats.map(s => {
          val stats = if (s.field == "points") s.value * leagueRepo.getCurrentPeriod(league).get.multiplier else s.value
          val statFieldId = leagueRepo.getStatFieldId(league.leagueId, s.field).get
          (StatsRow(resultRepo.insertStats(resultId, statFieldId, stats), statFieldId, s.value), ip.id)
        })})
      }
      else {
        pickees.zip(resultIds).flatMap({ case (ip, resultId) =>
          val pointsStatFieldId = leagueRepo.getStatFieldId(league.leagueId, "points").get
          var pointsTotal = 0.0
          ip.stats.flatMap(s => {
            val statFieldId = leagueRepo.getStatFieldId(league.leagueId, s.field).get
            val limitIds = pickeeRepo.getPickeeLimitIds(ip.id)
            val pointsForStat = leagueRepo.getPointsForStat(
              statFieldId, limitIds
            )
            pointsForStat.map(pfs => {
              pointsTotal += s.value * leagueRepo.getCurrentPeriod(league).get.multiplier * pfs
              (StatsRow(resultRepo.insertStats(resultId, statFieldId, s.value), statFieldId, s.value), ip.id)
            })
          }) ++ Seq((StatsRow(resultRepo.insertStats(resultId, pointsStatFieldId, pointsTotal), pointsStatFieldId, pointsTotal), ip.id))
        })

      }
    }, c, InternalServerError("Internal server error adding result"))
  }

  private def updateStats(newStats: List[(StatsRow, Long)], league: LeagueRow, period: Int, targetedAtTstamp: LocalDateTime)
                         (implicit c: Connection): Either[Result, Any] = {
    // dont need to worry about race conditions, because we use targetat tstamp, so it always updates against a consistent team
    // i.e. transfer between update calls, cant let it add points for hafl of one team, then half of a another for other update call
    // TODO can we just remove the transfer table if we're allowing future teams, with future timespans to exist?
    tryOrResponseRollback(
      newStats.foreach({ case (s, pickeeId) => {
        logger.info(s"Updating stats: $s, ${s.value} for internal pickee id: $pickeeId")
        println(s"Updating stats: $s, ${s.value} for internal pickee id: $pickeeId. targeting $targetedAtTstamp")
        val pickeeQ =
          s"""update pickee_stat_period psd set value = value + {newStats} from pickee p
         join pickee_stat ps using(pickee_id)
         where (period is NULL or period = {period}) and ps.pickee_stat_id = psd.pickee_stat_id and
          p.pickee_id = {pickeeId} and ps.stat_field_id = {statFieldId} and league_id = {leagueId};
         """
        SQL(pickeeQ).on(
          "newStats" -> s.value, "period" -> period, "pickeeId" -> pickeeId, "statFieldId" -> s.statFieldId,
          "targetedAtTstamp" -> targetedAtTstamp, "leagueId" -> league.leagueId
        ).executeUpdate()
          val q =
            s"""update user_stat_period usp set value = value + ({newStats} * coalesce(cbm.multiplier, 1.0)) from useru u
         join league l using(league_id)
         join card c using(user_id)
         join team t using(card_id)
         left join card_bonus_multiplier cbm on (cbm.card_id = c.card_id AND cbm.stat_field_id = {statFieldId})
         join pickee p using(pickee_id)
         join user_stat us using(user_id)
         where (period is NULL or period = {period}) and us.user_stat_id = usp.user_stat_id and
                t.timespan @> {period} and p.pickee_id = {pickeeId} and us.stat_field_id = {statFieldId}
               and l.league_id = {leagueId} and
               (u.late_entry_lock_ts is NULL OR u.late_entry_lock_ts < {targetedAtTstamp}) and NOT u.eliminated;
              """
          //(select count(*) from team_pickee where team_pickee.team_id = t.team_id) = l.team_size;
          val sql = SQL(q).on(
            "newStats" -> s.value, "period" -> period, "pickeeId" -> pickeeId, "statFieldId" -> s.statFieldId,
            "targetedAtTstamp" -> targetedAtTstamp, "leagueId" -> league.leagueId
          )
          //println(sql.sql)
           sql.executeUpdate()
        //println(changed)
    }}), c, InternalServerError("Internal server error updating stats"))
  }

  def getSeriesReq(leagueId: String) = (new LeagueAction(leagueId)).async { implicit request =>
    Future{
      db.withConnection { implicit c =>
        (for {
          period <- tryOrResponse[Option[Int]](request.getQueryString("period").map(_.toInt), BadRequest("Invalid period format"))
          results = resultRepo.getSeries(request.league.leagueId, period).toList
          success = Ok(Json.toJson(results))
        } yield success).fold(identity, identity)
      }
    }
  }

  def getPredictionsReq(leagueId: String, userId: String) = (new LeagueAction(leagueId) andThen new UserAction(userRepo, db)(userId).apply()).async{
    implicit request =>
      Future{
        (for {
          // TODO getOrElse next period?
          period <- tryOrResponse[Option[Int]](request.getQueryString("period").map(_.toInt), BadRequest("Invalid period format"))
          results = db.withConnection { implicit c => resultRepo.getUserPredictions(request.user.userId, period.getOrElse(1)).toList}
          success = Ok(Json.toJson(results))
        } yield success).fold(identity, identity)
      }
  }

  def upsertPredictionsReq(leagueId: String, userId: String) = (new AuthAction() andThen
    Auther.AuthLeagueAction(leagueId) andThen Auther.PermissionCheckAction andThen
    new UserAction(userRepo, db)(userId).auth()).async{
    implicit request =>

      def failure(badForm: Form[PredictionsFormInput]) = {Future.successful(BadRequest(badForm.errorsAsJson))}

      def success(input: PredictionsFormInput) = {
        Future {
          val results = db.withConnection { implicit c => resultRepo.upsertUserPredictions(
            request.user.userId, request.league.leagueId, input.predictions
          )}
          results.fold(l => BadRequest(l), r => Ok(Json.toJson(r)))
        }
      }
      predictionsForm.bindFromRequest().fold(failure, success)
  }

  def findByTeams(leagueId: String) = (new LeagueAction(leagueId)).async{
    implicit request =>
      def failure(badForm: Form[FindByTeamsFormInput]) = {Future.successful(BadRequest(badForm.errorsAsJson))}

      def success(input: FindByTeamsFormInput) = {
        Future {
          val match_ = db.withConnection { implicit c => resultRepo.findSeriesByTeams(
            request.league.leagueId, input.teamOne, input.teamTwo, input.includeReversedTeams
          )}
          Ok(Json.toJson(match_))
        }
      }
      findByTeamsForm.bindFromRequest().fold(failure, success)
  }
}
