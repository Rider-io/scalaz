package scalaz
package std

import _root_.java.util.concurrent.Executors

import org.scalacheck.Arbitrary
import org.scalacheck.Arbitrary._
import org.scalacheck.Prop.forAll

import scalaz.scalacheck.ScalazProperties._
import scalaz.scalacheck.ScalazArbitrary._
import scalaz.scalacheck.ScalaCheckBinding._
import scalaz.std.AllInstances._
import scalaz.syntax.functor._
import scalaz.Tags._

import scala.concurrent._
import scala.concurrent.duration._

class FutureTest extends SpecLite {

  val duration: Duration = 1.seconds

  implicit val throwableEqual: Equal[Throwable] = Equal.equalA[Throwable]

 {
  import scala.concurrent.ExecutionContext.Implicits.global

  implicit def futureEqual[A : Equal] = Equal[Throwable \/ A] contramap { future: Future[A] =>
    val futureWithError = future.map(\/-(_)).recover { case e => -\/(e) }
    Await.result(futureWithError, duration)
  }

  def FutureArbitrary[A](implicit a: Arbitrary[A]): Arbitrary[Future[A]] = implicitly[Arbitrary[A]] map { x => Future.successful(x) }

  case class SomeFailure(n: Int) extends Exception

  implicit val ArbitraryThrowable: Arbitrary[Throwable] = Arbitrary(arbitrary[Int].map(SomeFailure))

  checkAll(monoid.laws[Future[Int]])
  checkAll(monoid.laws[Future[Int @@ Multiplication]])

  // can not use `org.scalacheck.Arbitrary.arbFuture` due to https://github.com/scalaz/scalaz/issues/964
  // https://github.com/rickynils/scalacheck/blob/1.12.4/src/main/scala/org/scalacheck/Arbitrary.scala#L291-L293
  // For some reason ArbitraryThrowable isn't being chosen by scalac, so we give it explicitly.
  checkAll(monadError.laws[λ[(α, β) => Future[β]], Throwable](implicitly, FutureArbitrary, FutureArbitrary, implicitly, ArbitraryThrowable))

  // Scope these away from the rest as Comonad[Future] is a little evil.
  // Should fail to compile by default: implicitly[Comonad[Future]]
  {
    implicit val cm: Comonad[Future] = futureComonad(duration)
    checkAll(comonad.laws[Future])
  }
 }

  "Nondeterminism[Future]" should {
    implicit val es: ExecutionContext = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(1))

    "fetch first completed future in chooseAny" ! forAll { (xs: Vector[Int]) =>
      val promises = Vector.fill(xs.size)(Promise[Int]())
      def loop(is: List[Int], fs: Seq[Future[Int]], acc: Vector[Int]): Future[Vector[Int]] =
        is match {
          case i :: is0 =>
            promises(i).complete(scala.util.Try(xs(i)))
            Nondeterminism[Future].chooseAny(fs).get.flatMap { case (x, fs0) =>
              loop(is0, fs0, acc :+ x)
            }
          case Nil =>
            Future(acc)
        }

      val sorted = xs.zipWithIndex.sorted
      val sortedF = loop(sorted.map(_._2).toList, promises.map(_.future), Vector.empty)
      Await.result(sortedF, duration) must_== sorted.map(_._1)
    }

    "gather maintains order" ! forAll { (xs: List[Int]) =>
      val promises = Vector.fill(xs.size)(Promise[Int]())
      val f = Nondeterminism[Future].gather(promises.map(_.future))
      (promises zip xs).reverseIterator.foreach { case (p, x) =>
        p.complete(scala.util.Try(x))
      }
      Await.result(f, duration) must_== xs
    }
  }
}