package v1.league

import java.sql.Connection

import javax.inject.{Inject, Singleton}
import java.time.LocalDateTime
//import java.math.BigDecimal
import play.api.libs.concurrent.CustomExecutionContext
import play.api.libs.json._
import play.api.mvc.Result
import play.api.mvc.Results.{BadRequest, InternalServerError}
import anorm._
import anorm.{ Macro, RowParser }, Macro.ColumnNaming

import akka.actor.ActorSystem
import models._

class LeagueExecutionContext @Inject()(actorSystem: ActorSystem) extends CustomExecutionContext(actorSystem, "repository.dispatcher")

case class LeagueFull(league: PublicLeagueRow, limits: Map[String, Iterable[LimitRow]], periods: Iterable[PeriodRow], currentPeriod: Option[PeriodRow], statFields: Iterable[String])

object LeagueFull{
  implicit val implicitWrites = new Writes[LeagueFull] {
    def writes(league: LeagueFull): JsValue = {
      Json.obj(
        "id" -> league.league.leagueId,
        "name" -> league.league.leagueName,
        "gameId" -> league.league.gameId,
        "tournamentId" -> league.league.tournamentId,
        "isPrivate" -> league.league.isPrivate,
        "tournamentId" -> league.league.tournamentId,
        "teamSize" -> league.league.teamSize,
        "transferLimit" -> league.league.transferLimit, // use -1 for no transfer limit I think. only applies after period 1 start
        "transferWildcard" -> league.league.transferWildcard,
        "transferOpen" -> league.league.transferOpen,
        "transferDelayMinutes" -> league.league.transferDelayMinutes,
        "transferBlockedDuringPeriod" -> league.league.transferBlockedDuringPeriod,
        "startingMoney" -> league.league.startingMoney,
        "statFields" -> league.statFields,
        "limitTypes" -> league.limits,
        "periods" -> league.periods,
        "currentPeriod" -> league.currentPeriod,
        "started" -> league.league.started,
        "ended" -> (league.currentPeriod.exists(_.ended) && league.currentPeriod.exists(_.nextPeriodId.isEmpty)),
        "pickeeDescription" -> league.league.pickeeDescription,
        "periodDescription" -> league.league.periodDescription,
        "noWildcardForLateRegister" -> league.league.noWildcardForLateRegister,
        "applyPointsAtStartTime" -> league.league.applyPointsAtStartTime,
        "url" -> {if (league.league.urlVerified) league.league.url else ""}
      )
    }
  }
}


trait LeagueRepo{
  def get(leagueId: Long)(implicit c: Connection): Option[LeagueRow]
  def getWithRelated(leagueId: Long)(implicit c: Connection): LeagueFull
  def insert(formInput: LeagueFormInput)(implicit c: Connection): LeagueRow
  def update(league: LeagueRow, input: UpdateLeagueFormInput)(implicit c: Connection): LeagueRow
  def getStatFields(leagueId: Long)(implicit c: Connection): Iterable[LeagueStatFieldRow]
  def isStarted(league: LeagueRow): Boolean
  def insertStatField(leagueId: Long, name: String)(implicit c: Connection): Long
  def insertLeaguePrize(leagueId: Long, description: String, email: String)(implicit c: Connection): Long
  def insertPeriod(leagueId: Long, input: PeriodInput, period: Int, nextPeriodId: Option[Long])(implicit c: Connection): Long
  def getPeriod(periodId: Long)(implicit c: Connection): Option[PeriodRow]
  def getPeriods(leagueId: Long)(implicit c: Connection): Iterable[PeriodRow]
  def getPeriodFromValue(leagueId: Long, value: Int)(implicit c: Connection): PeriodRow
  def getPeriodFromTimestamp(leagueId: Long, time: LocalDateTime)(implicit c: Connection): Option[PeriodRow]
  def getCurrentPeriod(league: LeagueRow)(implicit c: Connection): Option[PeriodRow]
  def getNextPeriod(league: LeagueRow)(implicit c: Connection): Either[Result, PeriodRow]
  def detailedLeagueQueryExtractor(rows: Iterable[DetailedLeagueRow]): LeagueFull // TODO private
  def updatePeriod(
                    leagueId: Long, periodValue: Int, start: Option[LocalDateTime], end: Option[LocalDateTime],
                    multiplier: Option[Double])(implicit c: Connection): Int
  def postStartPeriodHook(league: LeagueRow, period: PeriodRow, timestamp: LocalDateTime)(
    implicit c: Connection, updateHistoricRanks: Long => Unit
  )
  def postEndPeriodHook(periodIds: Iterable[Long], leagueIds: Iterable[Long], timestamp: LocalDateTime)(implicit c: Connection)
  def startPeriods(currentTime: LocalDateTime)(implicit c: Connection, updateHistoricRanksFunc: Long => Unit)
  def endPeriods(currentTime: LocalDateTime)(implicit c: Connection)
  def insertLimits(leagueId: Long, limits: Iterable[LimitTypeInput])(implicit c: Connection): Map[String, Long]
  def getStatFieldId(leagueId: Long, statFieldName: String)(implicit c: Connection): Option[Long]
  def getStatFieldName(statFieldId: Long)(implicit c: Connection): Option[String]
}

@Singleton
class LeagueRepoImpl @Inject()(implicit ec: LeagueExecutionContext) extends LeagueRepo{

  private val periodParser: RowParser[PeriodRow] = Macro.namedParser[PeriodRow](ColumnNaming.SnakeCase)
  private val leagueParser: RowParser[LeagueRow] = Macro.namedParser[LeagueRow](ColumnNaming.SnakeCase)
  private val detailedLeagueParser: RowParser[DetailedLeagueRow] = Macro.namedParser[DetailedLeagueRow](ColumnNaming.SnakeCase)

  override def get(leagueId: Long)(implicit c: Connection): Option[LeagueRow] = {
    SQL(
      s"""select league_id, league_name, api_key, game_id, is_private, tournament_id, pickee_description,
        |period_description, transfer_limit, transfer_wildcard, starting_money, team_size, transfer_delay_minutes, transfer_open,
        |transfer_blocked_during_period, url, url_verified, current_period_id, apply_points_at_start_time,
        | no_wildcard_for_late_register
        | from league where league_id = $leagueId;""".stripMargin).as(leagueParser.singleOpt)
  }


  override def getWithRelated(leagueId: Long)(implicit c: Connection): LeagueFull = {
    val queryResult = SQL(s"""select league_id, name as league_name, game_id, is_private, tournament_id, pickee_description, period_description,
          transfer_limit, transfer_wildcard, starting_money, team_size, transferDelayMinutes, transfer_open, transfer_blocked_during_period,
          url, url_verified, apply_points_at_start_time, no_wildcard_for_late_register,
           (cp is null) as started, (cp is not null and upper(cp.timestpan) < now()) as ended,
           p.value as period_value, lower(timespan) as start, upper(timespan) as "end", multiplier, (p.period_id = l.current_period_id) as current, sf.name as stat_field_name,
           lt.name as limit_type_name, lt.description, l.name as limit_name, l."max" as limit_max
 | from league l
 | join period p using(league_id)
 | left join limit_type lt using(league_id)
 | left join current_period cp on (cp.league_id = l.league_id and cp.period_id = l.current_period_id)
           left join "limit" lim using(limit_type_id)
           join stat_field sf using(league_id)
        where league_id = $leagueId order by p.value, pck.external_pickee_id;""").as(detailedLeagueParser.*)
    detailedLeagueQueryExtractor(queryResult)
        // deconstruct tuple
        // check what db queries would actuallly return
  }

  override def getStatFields(leagueId: Long)(implicit c: Connection): Iterable[LeagueStatFieldRow] = {
    val lsfParser: RowParser[LeagueStatFieldRow] = Macro.namedParser[LeagueStatFieldRow](ColumnNaming.SnakeCase)
    val q = s"select stat_field_id, league_id, name from stat_field where league_id = $leagueId;"
    SQL(q).as(lsfParser.*)
  }

  override def insert(input: LeagueFormInput)(implicit c: Connection): LeagueRow = {
    println("Inserting new league")
    val q = SQL(
      """insert into league(league_name, api_key, game_id, is_private, tournament_id, pickee_description, period_description, transfer_limit,
        |transfer_wildcard, starting_money, team_size, transfer_blocked_during_period, transfer_open,
        |transfer_delay_minutes, url, url_verified, current_period_id, apply_points_at_start_time,
        |no_wildcard_for_late_register) values ({name}, {apiKey}, {gameId}, {isPrivate}, {tournamentId},
        | {pickeeDescription}, {periodDescription}, {transferLimit}, {transferWildcard},
        | {startingMoney}, {teamSize}, {transferBlockedDuringPeriod}, false, {transferDelayMinutes}, {url}, false, null,
        |  {applyPointsAtStartTime},
        | {noWildcardForLateRegister}) returning league_id;""".stripMargin
    ).on("name" -> input.name, "apiKey" -> input.apiKey, "gameId" -> input.gameId, "isPrivate" -> input.isPrivate,
      "tournamentId" -> input.tournamentId, "pickeeDescription" -> input.pickeeDescription,
      "periodDescription" -> input.periodDescription, "transferLimit" -> input.transferInfo.transferLimit,
      "transferWildcard" -> input.transferInfo.transferWildcard, "startingMoney" -> input.startingMoney,
      "teamSize" -> input.teamSize, "transferBlockedDuringPeriod" -> input.transferInfo.transferBlockedDuringPeriod,
      "transferDelayMinutes" -> input.transferInfo.transferDelayMinutes, "url" -> input.url.getOrElse(""),
      "applyPointsAtStartTime" -> input.applyPointsAtStartTime,
      "noWildcardForLateRegister" -> input.transferInfo.noWildcardForLateRegister)
    println(q.sql)
    println(q)
    val newLeagueId: Option[Long]= q.executeInsert()
    println(newLeagueId)
    // TODO maybe better do returning
    LeagueRow(newLeagueId.get, input.name, input.apiKey, input.gameId, input.isPrivate,
       input.tournamentId,  input.pickeeDescription,
      input.periodDescription, input.transferInfo.transferLimit,
      input.transferInfo.transferWildcard, input.startingMoney,
      input.teamSize, input.transferInfo.transferDelayMinutes, false, input.transferInfo.transferBlockedDuringPeriod,
      input.url.getOrElse(""), false, null,
      input.applyPointsAtStartTime, input.transferInfo.noWildcardForLateRegister)
  }

  override def update(league: LeagueRow, input: UpdateLeagueFormInput)(implicit c: Connection): LeagueRow = {
    // TODO update update!!! hehe
    var setString: String = ""
    var params: collection.mutable.Seq[NamedParameter] =
      collection.mutable.Seq(NamedParameter("leagueId", league.leagueId))
    if (input.name.isDefined) {
      setString += ", league_name = '{leagueName}'"
      params = params :+ NamedParameter("leagueName", input.name.get)
    }
    if (input.isPrivate.isDefined) {
      setString += ", league_name = {isPrivate}"
      params = params :+ NamedParameter("isPrivate", input.isPrivate.get)
    }
    if (input.transferOpen.isDefined) {
      setString += ", transfer_open = {transferOpen}"
      params = params :+ NamedParameter("transferOpen", input.transferOpen.get)
    }
    if (input.transferBlockedDuringPeriod.isDefined) {
      setString += ", transfer_blocked_during_period = {transferBlockedDuringPeriod}"
      params = params :+ NamedParameter("transferBlockedDuringPeriod", input.transferBlockedDuringPeriod.get)
    }
    if (input.transferDelayMinutes.isDefined) {
      setString += ", transfer_delay_minutes = {transferDelayMinutes}"
      params = params :+ NamedParameter("transferDelayMinutes", input.transferDelayMinutes.get)
    }
    if (input.periodDescription.isDefined) {
      setString += ", period_description = '{periodDescription}'"
      params = params :+ NamedParameter("periodDescription", input.periodDescription.get)
    }
    if (input.pickeeDescription.isDefined) {
      setString += ", pickee_description = '{pickeeDescription}'"
      params = params :+ NamedParameter("pickeeDescription", input.pickeeDescription.get)
    }
    if (input.transferLimit.isDefined) {
      setString += ", transfer_limit = {transferLimit}"
      params = params :+ NamedParameter("transferLimit", input.transferLimit.get)
    }
    if (input.transferWildcard.isDefined) {
      setString += ", transfer_wildcard = {transferWildcard}"
      params = params :+ NamedParameter("transferWildcard", input.transferWildcard.get)
    }
    if (input.applyPointsAtStartTime.isDefined) {
      setString += ", apply_points_at_start_time = {applyPointsAtStartTime}"
      params = params :+ NamedParameter("applyPointsAtStartTime", input.applyPointsAtStartTime.get)
    }
    if (input.url.isDefined) {
      setString += ", url = {url}"
      params = params :+ NamedParameter("url", input.url.get)
      setString += ", url_verified = false"
    }
    setString = setString.tail  // remove starting comma
    SQL(
      "update league set " + setString + " WHERE league_id = {leagueId}"
    ).on(params:_*).executeUpdate()
    // TODO returning, or overwrite league row
    get(league.leagueId).get
  }

  override def isStarted(league: LeagueRow): Boolean = league.currentPeriodId.nonEmpty

  override def insertLeaguePrize(leagueId: Long, description: String, email: String)(implicit c: Connection): Long = {
    val q = "insert into league_prize(league_id, description, email) values ({leagueId}, {description}, {email}) returning league_prize_id;"
    SQL(q).on("leagueId" -> leagueId, "description" -> description, "email" -> email).executeInsert().get
  }

  override def insertStatField(leagueId: Long, name: String)(implicit c: Connection): Long = {
    println("inserting stat field")
    val q = "insert into stat_field(league_id, name) values ({leagueId}, {name}) returning stat_field_id;"
    val out = SQL(q).on("leagueId" -> leagueId, "name" -> name).executeInsert().get
    println("inserted stat field")
    out
  }

  override def insertPeriod(leagueId: Long, input: PeriodInput, period: Int, nextPeriodId: Option[Long])(implicit c: Connection): Long = {
    val q =
      s"""insert into period(league_id, value, timespan, multiplier, next_period_id, ended) values (
        |{leagueId}, {period}, tstzrange({start}, {end}), {multiplier}, {nextPeriodId}, false
        |) returning period_id;""".stripMargin
    SQL(q).on("leagueId" -> leagueId, "nextPeriodId" -> nextPeriodId, "period" -> period, "start" -> input.start,
    "end" -> input.end, "multiplier" -> input.multiplier).executeInsert().get
  }

  override def getPeriod(periodId: Long)(implicit c: Connection): Option[PeriodRow] = {
    val q = s"select * from period where period_id = $periodId;"
    SQL(q).as(periodParser.singleOpt)
  }

  override def getPeriods(leagueId: Long)(implicit c: Connection): Iterable[PeriodRow] = {
    val q = s"select * from period where league_id = $leagueId;"
    SQL(q).as(periodParser.*)
  }

  override def getPeriodFromValue(leagueId: Long, value: Int)(implicit c: Connection): PeriodRow = {
    val q = s"select * from period where league_id = $leagueId and value = $value;"
    SQL(q).as(periodParser.single)
  }

  override def getPeriodFromTimestamp(leagueId: Long, time: LocalDateTime)(implicit c: Connection): Option[PeriodRow] = {
    val q = """select * from period where league_id = {leagueId} and  timespan @> {time};"""
    SQL(q).on("leagueId" -> leagueId, "time" -> time).as(periodParser.singleOpt)
  }

  override def getCurrentPeriod(league: LeagueRow)(implicit c: Connection): Option[PeriodRow] = {
    val q = "select * from period where period_id = {periodId};"
    SQL(q).on("periodId" -> league.currentPeriodId).as(periodParser.singleOpt)
  }

  override def getNextPeriod(league: LeagueRow)(implicit c: Connection): Either[Result, PeriodRow] = {
    // check if is above max?
    getCurrentPeriod(league) match {
      case Some(p) if !p.ended => Left(BadRequest("Must end current period before start next"))
      case Some(p) => {
        p.nextPeriodId match {
          case Some(np) => getPeriod(np).toRight(InternalServerError(s"Could not find next period $np, for period ${p.periodId}"))
          case None => Left(BadRequest("No more periods left to start. League is over"))
        }
      }
      case None => {
        // TODO sort by value
        Right(getPeriods(league.leagueId).toList.minBy(_.value))
      }
    }
  }

  override def detailedLeagueQueryExtractor(rows: Iterable[DetailedLeagueRow]): LeagueFull = {
    val head = rows.head
    // TODO map between them func
    val league = PublicLeagueRow(
      head.leagueId, head.leagueName, head.gameId, head.isPrivate, head.tournamentId, head.pickeeDescription, head.periodDescription,
      head.transferLimit, head.transferWildcard, head.startingMoney, head.teamSize, head.transferDelayMinutes,
      head.transferOpen, head.transferBlockedDuringPeriod, head.url, head.urlVerified, head.applyPointsAtStartTime,
      head.noWildcardForLateRegister, head.started, head.ended
    )
    val statFields = rows.flatMap(_.statFieldName)
    // TODO add current
    val periods = rows.map(
      r => PeriodRow(-1, -1, r.periodValue, r.start, r.end, r.multiplier)
    )
    val currentPeriod = rows.withFilter(_.current).map(r => PeriodRow(-1, -1, r.periodValue, r.start, r.end, r.multiplier)).headOption
    // TODO think this filter before group by inefficient
    val limits: Map[String, Iterable[LimitRow]] = rows.filter(_.limitTypeName.isDefined).groupBy(_.limitTypeName.get).mapValues(
      v => v.map(x => LimitRow(x.limitName.get, x.limitMax.get))
    )
    LeagueFull(league, limits, periods, currentPeriod, statFields)
  }

  override def updatePeriod(
                             leagueId: Long, periodValue: Int, start: Option[LocalDateTime], end: Option[LocalDateTime],
                             multiplier: Option[Double])(implicit c: Connection): Int = {
    var setString: String = ""
    var params: collection.mutable.Seq[NamedParameter] =
      collection.mutable.Seq(
        NamedParameter("value", periodValue),
        NamedParameter("league_id", leagueId))

    (start, end) match {
      case (Some(s), Some(e)) => {
        setString += ", timespan = tstzrange({start}, {end}"
        params = params :+ NamedParameter("start", s)
        params = params :+ NamedParameter("end", e)
      }
      case (Some(s), None) => {
        setString += ", timespan = tstzrange({start}, upper(timespan)"
        params = params :+ NamedParameter("start", s)
      }
      case (None, Some(e)) => {
        setString += ", timespan = tstzrange(lower(timespan), {end}"
        params = params :+ NamedParameter("end", e)
      }
      case _ => {}
    }
    if (multiplier.isDefined) {
      setString += ", [multiplier] = {multiplier}"
      params = params :+ NamedParameter("multiplier", multiplier.get)
    }
    SQL(
      "update period set " + setString + " WHERE [value] = {value} and [league_id] = {league_id}"
    ).on(params:_*).executeUpdate()
  }

  override def postEndPeriodHook(periodIds: Iterable[Long], leagueIds: Iterable[Long], timestamp: LocalDateTime)(implicit c: Connection): Unit = {
    println("tmp")
    // TODO batch
    periodIds.foreach(periodId => {
      val q =
        """update period set ended = true, timespan = tstzrange(lower(timespan), {timestamp})
    where period_id = {periodId};
    """
      SQL(q).on("periodId" -> periodId, "timestamp" -> timestamp).executeUpdate()
    })
    leagueIds.foreach(lid =>
      SQL(
        s"update league set transferOpen = true where league_id = $lid;"
      ).executeUpdate()
    )
  }

  override def postStartPeriodHook(league: LeagueRow, period: PeriodRow, timestamp: LocalDateTime)(
    implicit c: Connection, updateHistoricRanks: Long => Unit): Unit = {
    println("tmp")
    SQL("""update period set timespan = tstzrange({timestamp}, upper(timespan)) where period_id = {periodId};""").on(
      "timestamp" -> timestamp, "periodId" -> period.periodId
    ).executeUpdate()

    val transferOpenSet = if (league.transferBlockedDuringPeriod) ", transfer_open = false" else ""
    SQL(
      s"update league set current_period_id = ${period.periodId} #$transferOpenSet where league_id = ${league.leagueId};"
    ).executeUpdate()
    if (period.value > 1) updateHistoricRanks(league.leagueId)
  }

  override def endPeriods(currentTime: LocalDateTime)(implicit c: Connection) = {
    val q =
      """select league_id, period_id from league l join period p on (
        |l.current_period_id = p.period_id and p.ended = false and upper(p.timespan) <= {currentTime} and p.next_period_id is not null);""".stripMargin
    val (leagueIds, periodIds) = SQL(q).on("currentTime" -> currentTime).as(
      (SqlParser.long("league_id") ~ SqlParser.long("period_id")).*
    ).map(x => (x._1, x._2)).unzip
    postEndPeriodHook(leagueIds, periodIds, currentTime)
  }
  override def startPeriods(currentTime: LocalDateTime)(
    implicit c: Connection, updateHistoricRanksFunc: Long => Unit
  ) = {
    // looking for period that a) isnt current period, b) isnt old ended period (so must be future period!)
    // and is future period that should have started...so lets start it
    val q =
      """select * from league l join period p using(league_id)
        |where (l.current_period_id is null or not(l.current_period_id == p.periodId)) and
        |p.ended = false and lower(p.timespan) <= {currentTime};""".stripMargin
    SQL(q).on("currentTime" -> currentTime).as((leagueParser ~ periodParser).*).
      foreach(x => postStartPeriodHook(x._1, x._2, currentTime))
  }

  override def insertLimits(leagueId: Long, limits: Iterable[LimitTypeInput])(implicit c: Connection): Map[String, Long] = {
    // TODO bulk insert
    limits.toList.map(ft => {
      // = leagueRepo.insertLimits
      val newLimitTypeId: Long = SQL(
        """insert into limit_type(league_id, name, description, "max") values({leagueId}, {name}, {description}, {max});""").on(
        "leagueId" -> leagueId, "name" -> ft.name, "description" -> ft.description.getOrElse(ft.name), "max" -> ft.max
      ).executeInsert().get
      ft.types.iterator.map(f => {
        val newLimitId = SQL("""insert into "limit"(faction_type_id, name, "max") values({factionTypeId}, {name}, {max});""").on(
          "factionTypeId" -> newLimitTypeId, "name" -> f.name, "max" -> ft.max.getOrElse(f.max.get)
        ).executeInsert().get
        f.name -> newLimitId
      }).toMap
    }).reduceOption(_ ++ _).getOrElse(Map[String, Long]())
  }

  override def getStatFieldId(leagueId: Long, statFieldName: String)(implicit c: Connection): Option[Long] = {
    SQL(
      "select stat_field_id from stat_field where league_id = {leagueId} and name = {statFieldName}"
    ).on("leagueId" -> leagueId, "statFieldName" -> statFieldName).as(SqlParser.long("stat_field_id").singleOpt)
  }

  override def getStatFieldName(statFieldId: Long)(implicit c: Connection): Option[String] = {
    SQL(
      s"select name from stat_field where stat_field_id = $statFieldId"
    ).as(SqlParser.str("name").singleOpt)
  }
}

