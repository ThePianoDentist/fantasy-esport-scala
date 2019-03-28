package utils

import java.time.LocalDateTime
import java.text.SimpleDateFormat

import scala.util.Try
import play.api.mvc.Result
import play.api.mvc.Results.BadRequest
import java.io._

object IdParser {
  def parseLongId(id: String, idName: String): Either[Result, Long] = {
    Try(id.toLong).toOption.toRight(BadRequest(f"Invalid $idName ID: $id"))
  }
  def parseIntId(id: String, idName: String): Either[Result, Int] = {
    Try(id.toInt).toOption.toRight(BadRequest(f"Invalid $idName ID: $id"))
  }
}

object TryHelper {

  def tryOrResponse[T](block: () => T, errorResponse: Result): Either[Result, T] = {
    try{
      Right(block())
    }
    catch {
      case e: Exception => {
        // https://alvinalexander.com/scala/how-convert-stack-trace-exception-string-print-logger-logging-log4j-slf4j
        val sw = new StringWriter
        e.printStackTrace(new PrintWriter(sw))
        println(sw.toString)
        Left(errorResponse)
      }
    }
  }
}


//https://stackoverflow.com/a/9608800
object GroupByOrderedImplicit {
  import collection.mutable.{LinkedHashMap, LinkedHashSet, Map => MutableMap}
  implicit class GroupByOrderedImplicitImpl[A](val t: Traversable[A]) extends AnyVal {
    def groupByOrdered[K](f: A => K): MutableMap[K, LinkedHashSet[A]] = {
      val map = LinkedHashMap[K,LinkedHashSet[A]]().withDefault(_ => LinkedHashSet[A]())
      for (i <- t) {
        val key = f(i)
        map(key) = map(key) + i
      }
      map
    }
  }
}

object Formatter {
  import play.api.libs.json._
  def timestampFormatFactory(formatStr: String): Format[LocalDateTime] = new Format[LocalDateTime] {
    val format = new SimpleDateFormat(formatStr)

    def reads(json: JsValue): JsResult[LocalDateTime] = JsSuccess(LocalDateTime.parse(format.parse(json.as[String])))

    def writes(ts: LocalDateTime) = JsString(format.format(ts))
  }
}