/**
 * Copyright (C) 2013 Typesafe <http://typesafe.com/>
 */
package activator.storage

import scala.reflect._

object CheckedCast {

  // this should match the widenings in Predef
  private def numericWidening[A <: Any: ClassTag](value: Any, valueBoxedClass: Class[_]): Option[A] = {
    val targetErasure = classTag[A].runtimeClass
    val optionalTargetBoxedClass = if (classOf[java.lang.Number].isAssignableFrom(targetErasure)) {
      Some(targetErasure)
    } else {
      boxedClasses.get(targetErasure)
    }
    optionalTargetBoxedClass flatMap { targetBoxedClass =>
      try {
        val result: Number = valueBoxedClass match {
          case k if k == classOf[java.lang.Byte] =>
            targetBoxedClass match {
              case t if t == classOf[java.lang.Short] => value.asInstanceOf[java.lang.Byte].toShort
              case t if t == classOf[java.lang.Integer] => value.asInstanceOf[java.lang.Byte].toInt
              case t if t == classOf[java.lang.Long] => value.asInstanceOf[java.lang.Byte].toLong
              case t if t == classOf[java.lang.Float] => value.asInstanceOf[java.lang.Byte].toFloat
              case t if t == classOf[java.lang.Double] => value.asInstanceOf[java.lang.Byte].toDouble
            }
          case k if k == classOf[java.lang.Short] =>
            targetBoxedClass match {
              case t if t == classOf[java.lang.Integer] => value.asInstanceOf[java.lang.Short].toInt
              case t if t == classOf[java.lang.Long] => value.asInstanceOf[java.lang.Short].toLong
              case t if t == classOf[java.lang.Float] => value.asInstanceOf[java.lang.Short].toFloat
              case t if t == classOf[java.lang.Double] => value.asInstanceOf[java.lang.Short].toDouble
            }
          case k if k == classOf[java.lang.Character] =>
            targetBoxedClass match {
              case t if t == classOf[java.lang.Integer] => value.asInstanceOf[java.lang.Character].toInt
              case t if t == classOf[java.lang.Long] => value.asInstanceOf[java.lang.Character].toLong
              case t if t == classOf[java.lang.Float] => value.asInstanceOf[java.lang.Character].toFloat
              case t if t == classOf[java.lang.Double] => value.asInstanceOf[java.lang.Character].toDouble
            }
          case k if k == classOf[java.lang.Integer] =>
            targetBoxedClass match {
              case t if t == classOf[java.lang.Long] => value.asInstanceOf[java.lang.Integer].toLong
              case t if t == classOf[java.lang.Float] => value.asInstanceOf[java.lang.Integer].toFloat
              case t if t == classOf[java.lang.Double] => value.asInstanceOf[java.lang.Integer].toDouble
            }
          case k if k == classOf[java.lang.Long] =>
            targetBoxedClass match {
              case t if t == classOf[java.lang.Float] => value.asInstanceOf[java.lang.Long].toFloat
              case t if t == classOf[java.lang.Double] => value.asInstanceOf[java.lang.Long].toDouble
            }
          case k if k == classOf[java.lang.Float] =>
            targetBoxedClass match {
              case t if t == classOf[java.lang.Double] => value.asInstanceOf[java.lang.Float].toDouble
            }
        }
        Some(result)
      } catch {
        case ex: MatchError =>
          None
      }
    } map {
      _.asInstanceOf[A]
    }
  }

  final private val unboxedClasses: Map[Class[_], Class[_]] = Map(
    classOf[java.lang.Integer] -> classOf[Int],
    classOf[java.lang.Double] -> classOf[Double],
    classOf[java.lang.Boolean] -> classOf[Boolean],
    classOf[java.lang.Long] -> classOf[Long],
    classOf[java.lang.Short] -> classOf[Short],
    classOf[java.lang.Character] -> classOf[Char],
    classOf[java.lang.Byte] -> classOf[Byte],
    classOf[java.lang.Float] -> classOf[Float],
    classOf[scala.runtime.BoxedUnit] -> classOf[Unit])

  final lazy private val boxedClasses: Map[Class[_], Class[_]] = unboxedClasses map { _.swap }

  // The trickiness here is that asInstanceOf[A] doesn't use the
  // manifest and thus doesn't do anything (no runtime type
  // check). So we have to do it by hand, special-casing
  // AnyVal primitives because Java Class.cast won't work
  // on them as desired.
  // CheckedCast[Seq[Foo]] does not check the sequence elements or
  // the type of Foo.
  //
  // This is all done with deprecated ClassManifest because it was
  // written for 2.9, and the new TypeTag stuff is not thread-safe.
  // Perhaps a macro-based solution would be better in 2.10 anyway.
  def apply[A <: Any: ClassTag](value: Any): A = {
    if (value == null) {
      if (classTag[A] <:< classTag[AnyVal]) {
        throw new ClassCastException("null can't be converted to AnyVal type " + classTag[A])
      } else {
        null.asInstanceOf[A]
      }
    } else {
      val klass = value.asInstanceOf[AnyRef].getClass
      val unboxedClass = unboxedClasses.getOrElse(klass, klass)

      /* value and the return value are always boxed because that's how
             * an Any is passed around; type A we're casting to may be boxed or
             * unboxed. For example, value is always java.lang.Integer for
             * ints, but A could be java.lang.Integer OR scala Int. But
             * even if A is Int, the return value is really always a
             * java.lang.Integer, so we can leave the value boxed.
             */

      if (classTag[A].runtimeClass.isAssignableFrom(unboxedClass) ||
        classTag[A].runtimeClass.isAssignableFrom(klass)) {
        value.asInstanceOf[A]
      } else {
        val numericValue = numericWidening[A](value, klass)

        numericValue.getOrElse(throw new ClassCastException("Requested " + classTag[A] + " but value is " + value + " with type " +
          klass.getName))
      }
    }
  }
}
