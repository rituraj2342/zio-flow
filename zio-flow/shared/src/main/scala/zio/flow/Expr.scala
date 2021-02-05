package zio.flow
import scala.language.implicitConversions

trait Schema[A]
object Schema {
  implicit def nilSchema: Schema[Nil.type]                              = ???
  implicit def listSchema[A: Schema]: Schema[List[A]]                   = ???
  implicit def stringSchema: Schema[String]                             = ???
  implicit def intSchema: Schema[Int]                                   = ???
  implicit def unitSchema: Schema[Unit]                                 = ???
  implicit def boolSchema: Schema[Boolean]                              = ???
  implicit def leftSchema[A: Schema]: Schema[Left[A, Nothing]]          = ???
  implicit def rightSchema[B: Schema]: Schema[Right[Nothing, B]]        = ???
  implicit def schemaTuple2[A: Schema, B: Schema]: Schema[(A, B)]       = ???
  implicit def schemaEither[A: Schema, B: Schema]: Schema[Either[A, B]] = ???
  implicit def schemaNothing: Schema[Nothing]                           = ???
}

sealed trait Expr[+A] { self =>
  final def _1[X, Y](implicit ev: A <:< (X, Y)): Expr[X] =
    Expr.First(self.widen[(X, Y)])

  final def _2[X, Y](implicit ev: A <:< (X, Y)): Expr[Y] =
    Expr.Second(self.widen[(X, Y)])

  final def ->[B](that: Expr[B]): Expr[(A, B)] = Expr.tuple2((self, that))

  final def +(that: Expr[Int])(implicit ev: A <:< Int): Expr[Int] =
    Expr.AddInt(self.widen[Int], that)

  final def widen[B](implicit ev: A <:< B): Expr[B] = {
    val _ = ev

    self.asInstanceOf[Expr[B]]
  }
}
object Expr           {
  final case class Literal[A](value: A, schema: Schema[A])                extends Expr[A]
  final case class Variable[A](identifier: String)                        extends Expr[A]
  final case class AddInt(left: Expr[Int], right: Expr[Int])              extends Expr[Int]
  final case class Either0[A, B](either: Either[Expr[A], Expr[B]])        extends Expr[Either[A, B]]
  final case class Tuple2[A, B](left: Expr[A], right: Expr[B])            extends Expr[(A, B)]
  final case class Tuple3[A, B, C](_1: Expr[A], _2: Expr[B], _3: Expr[C]) extends Expr[(A, B, C)]
  final case class First[A, B](tuple: Expr[(A, B)])                       extends Expr[A]
  final case class Second[A, B](tuple: Expr[(A, B)])                      extends Expr[B]

  implicit def apply[A: Schema](value: A): Expr[A] =
    Literal(value, implicitly[Schema[A]])

  implicit def tuple2[A, B](t: (Expr[A], Expr[B])): Expr[(A, B)] =
    Tuple2(t._1, t._2)

  implicit def tuple3[A, B, C](t: (Expr[A], Expr[B], Expr[C])): Expr[(A, B, C)] =
    Tuple3(t._1, t._2, t._3)

  implicit def either[A, B](either0: Either[Expr[A], Expr[B]]): Expr[Either[A, B]] =
    Either0(either0)

  val unit: Expr[Unit] = Expr(())

  implicit def schemaExpr[A]: Schema[A] = ???
}
