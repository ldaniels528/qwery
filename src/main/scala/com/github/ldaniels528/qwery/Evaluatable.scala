package com.github.ldaniels528.qwery

/**
  * Represents an evaluatable value
  * @author lawrence.daniels@gmail.com
  */
trait Evaluatable {

  def compare(that: Evaluatable, data: Map[String, Any]): Int

  def evaluate(data: Map[String, Any]): Option[Any]

}

/**
  * Evaluatable Companion
  * @author lawrence.daniels@gmail.com
  */
object Evaluatable {

  def apply(value: Any): Evaluatable = value match {
    case v: Double => NumericValue(v)
    case v: String => StringValue(v)
    case t: Token => apply(t.value)
    case v =>
      throw new IllegalArgumentException(s"Invalid value type '$v' (${Option(v).map(_.getClass.getName).orNull})")
  }

  /**
    * Field Sequence Extensions
    * @param fields the given collection of fields
    */
  implicit class FieldSeqExtensions(val fields: Seq[Evaluatable]) extends AnyVal {

    @inline
    def isAllFields: Boolean = fields.exists {
      case field: Field => field.name == "*"
      case _ => false
    }

  }

}

/**
  * Represents a numeric value
  * @author lawrence.daniels@gmail.com
  */
case class NumericValue(value: Double) extends Evaluatable {

  override def compare(that: Evaluatable, data: Map[String, Any]): Int = {
    that match {
      case NumericValue(v) => value.compareTo(v)
      case StringValue(s) => value.toString.compareTo(s)
      case field: Field => field.compare(this, data)
      case unknown =>
        throw new IllegalStateException(s"Unhandled value '$unknown' (${Option(unknown).map(_.getClass.getName).orNull})")
    }
  }

  override def evaluate(data: Map[String, Any]): Option[Double] = Option(value)
}

/**
  * Represents a string value
  * @author lawrence.daniels@gmail.com
  */
case class StringValue(value: String) extends Evaluatable {

  override def compare(that: Evaluatable, data: Map[String, Any]): Int = {
    that match {
      case NumericValue(v) => value.compareTo(v.toString)
      case StringValue(v) => value.compareTo(v)
      case field: Field => field.compare(this, data)
      case unknown =>
        throw new IllegalStateException(s"Unhandled value '$unknown' (${Option(unknown).map(_.getClass.getName).orNull})")
    }
  }

  override def evaluate(data: Map[String, Any]): Option[String] = Option(value)
}

