package v1.pickee

import java.sql.Connection

import javax.inject.{Inject, Singleton}
import akka.actor.ActorSystem
import play.api.libs.concurrent.CustomExecutionContext
import play.api.libs.json._
import models._
import anorm._
import anorm.{Macro, RowParser}
import Macro.ColumnNaming
import play.api.Logger
import utils.GroupByOrderedImplicit._

class PickeeExecutionContext @Inject()(actorSystem: ActorSystem) extends CustomExecutionContext(actorSystem, "repository.dispatcher")

case class PickeeFormInput(id: Long, name: String, value: BigDecimal, active: Boolean, limits: List[String])

case class RepricePickeeFormInput(id: Long, price: BigDecimal)

case class RepricePickeeFormInputList(isInternalId: Boolean, pickees: List[RepricePickeeFormInput])

case class PickeeLimitsOut(pickee: PickeeRow, limits: Map[String, String])

case class DistinctPickee(pickeeId: Long, limitId: Long, pickeeName: String)

object PickeeLimitsOut {
  implicit val implicitWrites = new Writes[PickeeLimitsOut] {
    def writes(x: PickeeLimitsOut): JsValue = {
      Json.obj(
        "id" -> x.pickee.externalPickeeId,
        "name" -> x.pickee.pickeeName,
        "price" -> x.pickee.price,
        "limits" -> x.limits,
      )
    }
  }

  val parser: RowParser[PeriodRow] = Macro.namedParser[PeriodRow](ColumnNaming.SnakeCase)
}

trait PickeeRepo{
  def insertPickee(leagueId: Long, pickee: PickeeFormInput)(implicit c: Connection): Long
  def insertPickeeStat(statFieldId: Long, pickeeId: Long)(implicit c: Connection): Long
  def insertPickeeStatDaily(pickeeStatId: Long, period: Option[Int])(implicit c: Connection): Long
  def insertPickeeLimits(
                          pickees: Iterable[PickeeFormInput], newPickeeIds: Seq[Long], limitNamesToIds: Map[String, Long]
                        )(implicit c: Connection): Unit
  def getPickees(leagueId: Long)(implicit c: Connection): Iterable[PickeeRow]
  def getPickeesLimits(leagueId: Long)(implicit c: Connection): Iterable[PickeeLimitsOut]
  def getPickeeLimitIds(internalPickeeId: Long)(implicit c: Connection): Iterable[Long]
  def getPickeeStat(
                     leagueId: Long, statFieldId: Option[Long], period: Option[Int]
                   )(implicit c: Connection): Iterable[PickeeStatsOut]
  def getInternalId(leagueId: Long, externalPickeeId: Long)(implicit c: Connection): Option[Long]
  def updatePrice(leagueId: Long, externalPickeeId: Long, price: BigDecimal)(implicit c: Connection): Long
  def getRandomPickeesFromDifferentFactions(leagueId: Long)(implicit c: Connection): Iterable[Long]
  def getUserCards(leagueId: Long, userId: Long)(implicit c: Connection): Iterable[CardRow]
}

@Singleton
class PickeeRepoImpl @Inject()()(implicit ec: PickeeExecutionContext) extends PickeeRepo{
  private val logger = Logger("application")

  override def insertPickee(leagueId: Long, pickee: PickeeFormInput)(implicit c: Connection): Long = {
    SQL(
      s"""insert into pickee(league_id, pickee_name, external_pickee_id, price, active)
        | values($leagueId, {name}, ${pickee.id}, ${pickee.value}, ${pickee.active}) returning pickee_id""".stripMargin
    ).on("name" -> pickee.name).executeInsert().get
  }

  override def insertPickeeStat(statFieldId: Long, pickeeId: Long)(implicit c: Connection): Long = {
    SQL(
      s"insert into pickee_stat(stat_field_id, pickee_id, previous_rank) values($statFieldId, $pickeeId, 1) returning pickee_stat_id;"
    ).executeInsert().get
  }

  override def insertPickeeStatDaily(pickeeStatId: Long, period: Option[Int])(implicit c: Connection): Long = {
    SQL(
      "insert into pickee_stat_period(pickee_stat_id, period, value) values({pickeeStatId}, {period}, 0.0) returning pickee_stat_period_id;"
    ).on("pickeeStatId" -> pickeeStatId, "period" -> period).executeInsert().get
  }

  override def insertPickeeLimits(
                                   pickees: Iterable[PickeeFormInput], newPickeeIds: Seq[Long], limitNamesToIds: Map[String, Long]
                                 )(implicit c: Connection): Unit = {

    pickees.zipWithIndex.foreach({ case (p, i) => p.limits.foreach({
        // Try except key error
        f => SQL(s"insert into pickee_limit(limit_id, pickee_id) values (${limitNamesToIds(f)}, ${newPickeeIds(i)}) returning pickee_limit_id;").executeInsert().get
      })
    })
  }

  override def getPickees(leagueId: Long)(implicit c: Connection): Iterable[PickeeRow] = {
    SQL(s"select pickee_id as internal_pickee_id, external_pickee_id, pickee_name, price from pickee where league_id = $leagueId;").as(PickeeRow.parser.*)
 }


  override def getPickeesLimits(leagueId: Long)(implicit c: Connection): Iterable[PickeeLimitsOut] = {
    val rowParser: RowParser[PickeeLimitsRow] = Macro.namedParser[PickeeLimitsRow](ColumnNaming.SnakeCase)
    SQL(
      s"""select pickee_id as internal_pickee_id, external_pickee_id, p.pickee_name, price, lt.name as limit_type, l.name as limit_name, coalesce(lt.max, l.max)
        |from pickee p
        |left join limit_type lt using(league_id)
        |left join "limit" l using(limit_type_id)
        |where league_id = $leagueId;""".stripMargin).as(rowParser.*).groupBy(_.internalPickeeId).map({case(internalPickeeId, v) => {
      PickeeLimitsOut(PickeeRow(
        internalPickeeId, v.head.externalPickeeId, v.head.pickeeName, v.head.price
      ), v.map(lim => lim.limitType -> lim.limitName).toMap)
    }})
  }

  override def getPickeeLimitIds(internalPickeeId: Long)(implicit c: Connection): Iterable[Long] = {
    SQL("""select limit_id from "limit" join pickee using(pickee_id) where pickee_id = {internalPickeeId}""").on(
      "internalPickeeId" -> internalPickeeId
    ).as(SqlParser.long("limit_id").*)
  }

  override def getPickeeStat(
                                  leagueId: Long, statFieldId: Option[Long], period: Option[Int]
                                )(implicit c: Connection): Iterable[PickeeStatsOut] = {
    val rowParser: RowParser[PickeeLimitsAndStatsRow] = Macro.namedParser[PickeeLimitsAndStatsRow](ColumnNaming.SnakeCase)
    //order by p.price desc
    SQL(
      """
        |select pickee_id as internal_pickee_id, external_pickee_id, p.pickee_name, price, lt.name as limit_type, l.name as limit_name, coalesce(lt.max, l.max) as "max",
        |sf.name as stat_field_name, psd.value, ps.previous_rank
        |from pickee p join pickee_stat ps using(pickee_id) join pickee_stat_period psd using(pickee_stat_id)
        | join stat_field sf using(stat_field_id)
        | left join limit_type lt on(lt.league_id = p.league_id) left join "limit" l using(limit_type_id)
        | where p.league_id = {leagueId} and ({period} is null or period = {period}) and
        | ({statFieldId} is null or stat_field_id = {statFieldId})
        | order by pickee_name;
      """.stripMargin).on("leagueId" -> leagueId, "period" -> period, "statFieldId" -> statFieldId).
      as(rowParser.*).groupByOrdered(_.externalPickeeId).map({case(internalPickeeId, v) => {
      PickeeStatsOut(
        PickeeRow(internalPickeeId, v.head.externalPickeeId, v.head.pickeeName, v.head.price),
        v.withFilter(_.limitType.isDefined).map(lim => lim.limitType.get -> lim.limitName.get).toMap,
      v.map(s => s.statFieldName -> s.value).toMap
      )
    }})
  }

  override def getInternalId(leagueId: Long, externalPickeeId: Long)(implicit c: Connection): Option[Long] = {
    SQL(
      s"select pickee_id from pickee where league_id = $leagueId and external_pickee_id = $externalPickeeId;"
    ).as(SqlParser.long("pickee_id").singleOpt)
  }

  // TODO bulk func
  override def updatePrice(leagueId: Long, externalPickeeId: Long, price: BigDecimal)(implicit c: Connection): Long = {
    SQL(s"update pickee set price = $price where league_id = $leagueId and external_pickee_id = $externalPickeeId").executeUpdate()
  }

  override def getRandomPickeesFromDifferentFactions(leagueId: Long)(implicit c: Connection): Iterable[Long] = {
    val parser: RowParser[DistinctPickee] = Macro.namedParser[DistinctPickee](ColumnNaming.SnakeCase)
    var seenLimits = Set[Long]()
    var chosenOnes = Set[Long]()
    // TODO dont need get all rows
    val pickees = SQL(
      """select limit_id, pickee_id, pickee_name, random() as rando from pickee
        |left join pickee_limit pl using(pickee_id)
        |where league_id = {leagueId} order by rando""".stripMargin
    ).on("leagueId" -> leagueId).as(parser.*).iterator
    while (chosenOnes.size < 7){
      val nextPickee = pickees.next()
      logger.info(s"pickee: ${nextPickee.pickeeId}, limit: ${nextPickee.limitId}")
      if (!seenLimits.contains(nextPickee.limitId)){
        chosenOnes = chosenOnes + nextPickee.pickeeId
        seenLimits = seenLimits + nextPickee.limitId
      }
    }
    chosenOnes
  }

  override def getUserCards(leagueId: Long, userId: Long)(implicit c: Connection): Iterable[CardRow] = {
    // TODO kind of overlap with team repo
    SQL(
      """
        |select card_id, user_id, pickee_id, colour from card
        |join pickee using(pickee_id) where user_id = {userId} and league_id = {leagueId}
      """.stripMargin).on("userId" -> userId, "leagueId" -> leagueId).as(CardRow.parser.*)
  }
}

