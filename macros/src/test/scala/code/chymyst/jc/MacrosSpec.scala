package code.chymyst.jc

import Core._
import Macros.{getName, rawTree, m, b, go}
import org.scalatest.{BeforeAndAfterEach, FlatSpec, Matchers}

import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

class MacrosSpec extends FlatSpec with Matchers with BeforeAndAfterEach {

  val warmupTimeMs = 200L

  var tp0: Pool = _

  def waitSome(): Unit = Thread.sleep(warmupTimeMs)

  override def beforeEach(): Unit = {
    tp0 = new FixedPool(4)
  }

  override def afterEach(): Unit = {
    tp0.shutdownNow()
  }

  behavior of "reaction site"

  it should "track whether molecule emitters are bound" in {
    val a = new E("a123")
    val b = new E("b")
    val c = new E("")

    a.toString shouldEqual "a123"
    b.toString shouldEqual "b"
    c.toString shouldEqual "<no name>"

    a.isBound shouldEqual false
    b.isBound shouldEqual false
    c.isBound shouldEqual false

    site(go { case a(_) + c(_) => b() })

    a.isBound shouldEqual true
    b.isBound shouldEqual false
    c.isBound shouldEqual true

    val expectedReaction = "<no name> + a123 => ..."

    // These methods are private to the package!
    a.emittingReactions shouldEqual Set()
    b.emittingReactions.size shouldEqual 1
    b.emittingReactions.map(_.toString) shouldEqual Set(expectedReaction)
    c.emittingReactions shouldEqual Set()
    a.consumingReactions.get.size shouldEqual 1
    a.consumingReactions.get.head.toString shouldEqual expectedReaction
    b.consumingReactions shouldEqual None
    c.consumingReactions.get shouldEqual a.consumingReactions.get
  }

  behavior of "macros for defining new molecule emitters"

  it should "fail to compute correct names when molecule emitters are defined together" in {
    val (counter, fetch) = (m[Int], b[Unit, String])

    counter.name shouldEqual fetch.name
    counter.name should fullyMatch regex "x\\$[0-9]+"
  }

  it should "compute correct names and classes for molecule emitters" in {
    val a = m[Option[(Int, Int, Map[String, Boolean])]] // complicated type

    a.isInstanceOf[M[_]] shouldEqual true
    a.isInstanceOf[E] shouldEqual false
    a.toString shouldEqual "a"

    val s = b[Map[(Boolean, Unit), Seq[Int]], Option[List[(Int, Option[Map[Int, String]])]]] // complicated type

    s.isInstanceOf[B[_, _]] shouldEqual true
    s.isInstanceOf[EB[_]] shouldEqual false
    s.isInstanceOf[BE[_]] shouldEqual false
    s.isInstanceOf[EE] shouldEqual false
    s.toString shouldEqual "s/B"
  }

  it should "create an emitter of class E for m[Unit]" in {
    val a = m[Unit]
    a.isInstanceOf[E] shouldEqual true
  }

  it should "create an emitter of class BE[Int] for b[Int, Unit]" in {
    val a = b[Int, Unit]
    a.isInstanceOf[BE[Int]] shouldEqual true
  }

  it should "create an emitter of class EB[Int] for b[Unit, Int]" in {
    val a = b[Unit, Int]
    a.isInstanceOf[EB[Int]] shouldEqual true
  }

  it should "create an emitter of class EE for b[Unit, Unit]" in {
    val a = b[Unit, Unit]
    a.isInstanceOf[EE] shouldEqual true
  }

  behavior of "macros for inspecting a reaction body"

  it should "fail to compile a reaction with regrouped inputs" in {
    val a = m[Unit]
    a.isInstanceOf[E] shouldEqual true

    "val r = go { case a(_) + (a(_) + a(_)) => }" shouldNot compile
    "val r = go { case a(_) + (a(_) + a(_)) + a(_) => }" shouldNot compile
    "val r = go { case (a(_) + a(_)) + a(_) + a(_) => }" should compile
  }

  it should "correctly sort input molecules with compound values and Option" in {
    val bb = m[(Int, Option[Int])]
    val reaction = go { case bb((1, Some(2))) + bb((0, None)) => }
    reaction.info.toString shouldEqual "bb((0,None)) + bb((1,Some(2))) => "
  }

  it should "correctly sort input molecules with compound values" in {
    val bb = m[(Int, Int)]
    val reaction = go { case bb((1, 2)) + bb((0, 3)) + bb((4, _)) => }
    reaction.info.toString shouldEqual "bb((0,3)) + bb((1,2)) + bb(?) => "
  }

  it should "inspect reaction body with default clause that declares a singleton" in {
    val a = m[Int]

    val reaction = go { case _ => a(123) }

    reaction.info.inputs shouldEqual Nil
    reaction.info.guardPresence.effectivelyAbsent shouldEqual true
    reaction.info.outputs shouldEqual List(OutputMoleculeInfo(a, SimpleConstOutput(123)))
  }

  it should "inspect reaction body containing local molecule emitters" in {
    val a = m[Int]

    val reaction =
      go { case a(x) =>
        val q = new M[Int]("q")
        val s = new E("s")
        go { case q(_) + s(_) => }
        q(0)
      }
    reaction.info.inputs should matchPattern { case Array(InputMoleculeInfo(`a`, 0, SimpleVar('x, _), `simpleVarXSha1`)) => }
    reaction.info.outputs shouldEqual List()
  }

  it should "inspect reaction body with embedded join" in {
    val a = m[Int]
    val bb = m[Int]
    val f = b[Unit, Int]
    site(tp0)(
      go { case f(_, r) + bb(x) => r(x) },
      go { case a(x) =>
        val p = m[Int]
        site(tp0)(go { case p(y) => bb(y) })
        p(x + 1)
      }
    )
    a(1)
    f.timeout(1000 millis)() shouldEqual Some(2)
  }

  it should "inspect reaction body with embedded join and go" in {
    val a = m[Int]
    val bb = m[Int]
    val f = b[Unit, Int]
    site(tp0)(
      go { case f(_, r) + bb(x) => r(x) },
      go { case a(x) =>
        val p = m[Int]
        site(tp0)(go { case p(y) => bb(y) })
        p(x + 1)
      }
    )
    a(1)
    f.timeout(1000 millis)() shouldEqual Some(2)
  }

  val simpleVarXSha1 = ""
  val constantNoneSha1 = "6EEF6648406C333A4035CD5E60D0BF2ECF2606D7"
  val wildcardSha1 = ""
  val constantZeroSha1 = "8227489534FBEA1F404CAAEC9F4CCAEEB9EF2DC1"
  val constantOneSha1 = "356A192B7913B04C54574D18C28D46E6395428AB"

  it should "inspect a two-molecule reaction body with None" in {
    val a = m[Int]
    val bb = m[Option[Int]]

    val result = go { case a(x) + bb(None) => bb(None) }

    (result.info.inputs match {
      case Array(
        InputMoleculeInfo(`a`, 0, SimpleVar('x, _), sha_a),
        InputMoleculeInfo(`bb`, 1, SimpleConst(None), sha_bb)
      ) =>
        sha_a shouldEqual simpleVarXSha1
        sha_bb shouldEqual constantNoneSha1
        true
      case _ => false
    }) shouldEqual true

    result.info.outputs shouldEqual List(OutputMoleculeInfo(bb, SimpleConstOutput(None)))
    result.info.guardPresence shouldEqual GuardAbsent
    result.info.sha1 shouldEqual "435CBA662F8A4992849522C11B78BE206E8D29D4"
  }

  val ax_qq_reaction_sha1 = "84BE76228B9549230BCA620A56209B9BD1D0D25F"

  it should "inspect a two-molecule reaction body" in {
    val a = m[Int]
    val qq = m[Unit]

    val result = go { case a(x) + qq(_) => qq() }

    (result.info.inputs match {
      case Array(
      InputMoleculeInfo(`a`, 0, SimpleVar('x, _), `simpleVarXSha1`),
      InputMoleculeInfo(`qq`, 1, Wildcard, sha_qq)
      ) =>
        sha_qq shouldEqual wildcardSha1
        true
      case _ => false
    }) shouldEqual true
    result.info.outputs shouldEqual List(OutputMoleculeInfo(qq, SimpleConstOutput(())))
    result.info.guardPresence shouldEqual AllMatchersAreTrivial
    result.info.sha1 shouldEqual ax_qq_reaction_sha1
  }

  it should "compute reaction sha1 independently of input molecule order" in {
    val a = m[Int]
    val qq = m[Unit]

    val result = go { case a(x) + qq(_) => qq() }
    result.info.sha1 shouldEqual ax_qq_reaction_sha1

    // This reaction is different only in the order of input molecules, so its sha1 must be the same.
    val result2 = go { case qq(_)  + a(x)  => qq() }
    result2.info.sha1 shouldEqual ax_qq_reaction_sha1
  }

  it should "compute reaction sha1 independently of guard order" in {
    val a = m[Int]

    val result = go { case a(x) + a(y) if x > 1 && y > 1 => a(x+y) }

    // This reaction is different only in the order of guards, so its sha1 must be the same.
    val result2 = go { case a(x) + a(y) if y > 1 && x > 1 => a(x+y) }
    result.info.sha1 shouldEqual result2.info.sha1
  }

  it should "inspect a reaction body with another molecule and extra code" in {
    val a = m[Int]
    val qqq = m[String]

    object testWithApply {
      def apply(x: Int): Int = x + 1
    }

    val result = go {
      case a(_) + a(x) + a(1) =>
        a(x + 1)
        if (x > 0) a(testWithApply(123))
        println(x)
        qqq("")
    }

    (result.info.inputs match {
      case Array(
      InputMoleculeInfo(`a`, 0, Wildcard, `wildcardSha1`),
      InputMoleculeInfo(`a`, 1,SimpleVar('x, _), `simpleVarXSha1`),
      InputMoleculeInfo(`a`, 2, SimpleConst(1), sha_a)
      ) =>
        sha_a shouldEqual constantOneSha1
        true
      case _ => false
    }) shouldEqual true
    result.info.outputs shouldEqual List(OutputMoleculeInfo(a, OtherOutputPattern), OutputMoleculeInfo(a, OtherOutputPattern), OutputMoleculeInfo(qqq, SimpleConstOutput("")))
    result.info.guardPresence shouldEqual GuardAbsent
  }

  it should "inspect reaction body with embedded reaction" in {
    val a = m[Int]
    val qq = m[Unit]

    val result = go { case a(x) => go { case qq(_) => a(0) }; qq() }

    result.info.inputs should matchPattern {
      case Array(InputMoleculeInfo(`a`, 0, SimpleVar('x, _), `simpleVarXSha1`)) =>
    }
    result.info.outputs shouldEqual List(OutputMoleculeInfo(qq, SimpleConstOutput(())))
    result.info.guardPresence shouldEqual AllMatchersAreTrivial
  }

  it should "inspect a very complicated reaction input pattern" in {
    val a = m[Int]
    val c = m[Unit]
    val qq = m[Unit]
    val s = b[Unit, Int]
    val bb = m[(Int, Option[Int])]

    // reaction contains all kinds of pattern-matching constructions
    val result = go {
      case a(p) + a(y) + a(1) + c(()) + c(_) + bb( (0, None) ) + bb( (1, Some(2)) ) + bb((1, z)) + bb((_, None)) + bb((t, Some(q))) + s(_, r) => s(); a(p + 1); qq(); r(p)
    }

    result.info.inputs should matchPattern {
      case Array(
      InputMoleculeInfo(`a`, 0, SimpleVar('p, _), _),
      InputMoleculeInfo(`a`, 1, SimpleVar('y, _), _),
      InputMoleculeInfo(`a`, 2, SimpleConst(1), _),
      InputMoleculeInfo(`c`, 3, Wildcard, _),
      InputMoleculeInfo(`c`, 4, Wildcard, _),
      InputMoleculeInfo(`bb`, 5, SimpleConst((0, None)), _),
      InputMoleculeInfo(`bb`, 6, SimpleConst((1, Some(2))), _),
      InputMoleculeInfo(`bb`, 7, OtherInputPattern(_, List('z)), _),
      InputMoleculeInfo(`bb`, 8, OtherInputPattern(_, List()), _),
      InputMoleculeInfo(`bb`, 9, OtherInputPattern(_, List('t, 'q)), _),
      InputMoleculeInfo(`s`, 10, Wildcard, _)
      ) =>
    }
    result.info.outputs shouldEqual List(OutputMoleculeInfo(s, SimpleConstOutput(())), OutputMoleculeInfo(a, OtherOutputPattern), OutputMoleculeInfo(qq, SimpleConstOutput(())))

    result.info.toString shouldEqual "a(1) + a(p) + a(y) + bb((0,None)) + bb((1,Some(2))) + bb(?z) + bb(?) + bb(?t,q) + c(_) + c(_) + s/B(_) => s/B() + a(?) + qq()"
  }

  it should "not fail to define a reaction with correct inputs with non-default pattern-matching in the middle of reaction" in {
    val a = new M[Option[Int]]("a")
    val b = new E("b")
    val c = new E("c")

    site(tp0)(go { case b(_) + a(Some(x)) + c(_) => })

    a.logSoup shouldEqual "Site{a + b + c => ...}\nNo molecules"
  }

  it should "define a reaction with correct inputs with default pattern-matching in the middle of reaction" in {
    val a = new M[Option[Int]]("a")
    val b = new E("b")
    val c = new E("c")

    site(tp0)(go { case b(_) + a(None) + c(_) => })

    a.logSoup shouldEqual "Site{a + b + c => ...}\nNo molecules"
  }

  it should "define a reaction with correct inputs with non-simple default pattern-matching in the middle of reaction" in {
    val a = new M[Seq[Int]]("a")
    val b = new E("b")
    val c = new E("c")

    site(go { case b(_) + a(List()) + c(_) => })

    a.logSoup shouldEqual "Site{a + b + c => ...}\nNo molecules"
  }

  it should "not fail to define a simple reaction with correct inputs with empty option pattern-matching at start of reaction" in {
    val a = new M[Option[Int]]("a")
    val b = new E("b")
    val c = new E("c")

    site(tp0)(go { case a(None) + b(_) + c(_) => })

    a.logSoup shouldEqual "Site{a + b + c => ...}\nNo molecules"
  }

  it should "define a reaction with correct inputs with empty option pattern-matching at start of reaction" in {
    val a = new M[Option[Int]]("a")
    val b = new E("b")
    val c = new E("c")

    site(tp0)(go { case a(None) + b(_) + c(_) => })

    a.logSoup shouldEqual "Site{a + b + c => ...}\nNo molecules"
  }

  it should "define a reaction with correct inputs with non-default pattern-matching at start of reaction" in {
    val a = new M[Option[Int]]("a")
    val b = new E("b")
    val c = new E("c")

    site(tp0)(go { case a(Some(x)) + b(_) + c(_) => })

    a.logSoup shouldEqual "Site{a + b + c => ...}\nNo molecules"
  }

  it should "run reactions correctly with non-default pattern-matching at start of reaction" in {
    val a = m[Option[Int]]
    val f = b[Unit, Int]

    site(tp0)(go { case a(Some(x)) + f(_, r) => r(x) })

    a(Some(1))
    waitSome()
    waitSome()
    a.logSoup shouldEqual "Site{a + f/B => ...}\nMolecules: a(Some(1))"
    f.timeout(2.second)() shouldEqual Some(1)
    a.logSoup shouldEqual "Site{a + f/B => ...}\nNo molecules"
  }

  it should "not run a reaction whose static guard is false" in {
    val a = m[Option[Int]]
    val f = b[Unit, Int]

    val n = 1

    site(tp0)(go { case a(Some(x)) + f(_, r) if n < 1 => r(x) })

    a(Some(1))
    waitSome()
    waitSome()
    a.logSoup shouldEqual "Site{a + f/B => ...}\nMolecules: a(Some(1))"
    f.timeout(2.second)() shouldEqual None
    a.logSoup shouldEqual "Site{a + f/B => ...}\nMolecules: a(Some(1))"
  }

  it should "not run a reaction whose cross-molecule guard is false" in {
    val a = m[Option[Int]]
    val f = b[Int, Int]

    val n = 2

    site(tp0)(go { case a(Some(x)) + f(y, r) if x < y + n => r(x) })

    a(Some(10))
    waitSome()
    waitSome()
    a.logSoup shouldEqual "Site{a + f/B => ...}\nMolecules: a(Some(10))"
    f.timeout(2.second)(0) shouldEqual None
    a.logSoup shouldEqual "Site{a + f/B => ...}\nMolecules: a(Some(10))"
  }

  it should "run a reaction whose cross-molecule guard is true" in {
    val a = m[Option[Int]]
    val f = b[Int, Int]

    val n = 2

    site(tp0)(go { case a(Some(x)) + f(y, r) if x < y + n => r(x) })

    a(Some(1))
    waitSome()
    waitSome()
    a.logSoup shouldEqual "Site{a + f/B => ...}\nMolecules: a(Some(1))"
    f.timeout(2.second)(0) shouldEqual Some(1)
    a.logSoup shouldEqual "Site{a + f/B => ...}\nNo molecules"
  }

  it should "define a reaction with correct inputs with constant non-default pattern-matching at start of reaction" in {
    val a = new M[Int]("a")
    val b = new E("b")
    val c = new E("c")

    site(tp0)(go { case a(1) + b(_) + c(_) => })

    a.logSoup shouldEqual "Site{a + b + c => ...}\nNo molecules"
  }

  it should "define a reaction with correct inputs with constant default option pattern-matching at start of reaction" in {
    val a = new M[Option[Int]]("a")
    val b = new E("b")
    val c = new E("c")

    site(tp0)(go { case a(None) + b(_) + c(_) => })

    a.logSoup shouldEqual "Site{a + b + c => ...}\nNo molecules"
  }

  it should "determine constant input and output patterns correctly" in {
    val a = new M[Option[Int]]("a")
    val b = new M[String]("b")
    val c = new M[(Int, Int)]("c")
    val d = new E("d")
    val e = m[Either[Option[Int],String]]

    val r = go { case a(Some(1)) + b("xyz") + d(()) + c((2, 3)) + e(Left(Some(1))) + e(Right("input")) =>
      a(Some(2)); e(Left(Some(2))); e(Right("output"))
    }

    r.info.inputs should matchPattern {
      case Array(
      InputMoleculeInfo(`a`, 0, SimpleConst(Some(1)), _),
      InputMoleculeInfo(`b`, 1, SimpleConst("xyz"), _),
      InputMoleculeInfo(`d`, 2, Wildcard, _),
      InputMoleculeInfo(`c`, 3, SimpleConst((2,3)), _),
      InputMoleculeInfo(`e`, 4, SimpleConst(Left(Some(1))), _),
      InputMoleculeInfo(`e`, 5, SimpleConst(Right("input")), _)
      ) =>
    }
    r.info.outputs shouldEqual List(
      OutputMoleculeInfo(a, SimpleConstOutput(Some(2))),
      OutputMoleculeInfo(e, SimpleConstOutput(Left(Some(2)))),
      OutputMoleculeInfo(e, SimpleConstOutput(Right("output")))
    )
    r.info.guardPresence shouldEqual GuardAbsent
    r.info.sha1 shouldEqual "31B407C84D1871664635F7A7A7DEDF16B7BBE107"
  }

  it should "detect output molecules with constant values" in {
    val c = m[Int]
    val bb = m[(Int, Int)]
    val bbb = m[Int]
    val cc = m[Option[Int]]

    val r1 = go { case bbb(x) => c(x); bb((1, 2)); bb((3, x)) }
    val r2 = go { case bbb(_) + c(_) => bbb(0) }
    val r3 = go { case bbb(x) + c(_) + c(_) => bbb(1); c(x); bbb(2); cc(None); cc(Some(1)) }

    r1.info.outputs shouldEqual List(
      OutputMoleculeInfo(c, OtherOutputPattern),
      OutputMoleculeInfo(bb, SimpleConstOutput((1, 2))),
      OutputMoleculeInfo(bb, OtherOutputPattern)
    )
    r2.info.outputs shouldEqual List(OutputMoleculeInfo(bbb, SimpleConstOutput(0)))
    r3.info.outputs shouldEqual List(
      OutputMoleculeInfo(bbb, SimpleConstOutput(1)),
      OutputMoleculeInfo(c, OtherOutputPattern),
      OutputMoleculeInfo(bbb, SimpleConstOutput(2)),
      OutputMoleculeInfo(cc, SimpleConstOutput(None)),
      OutputMoleculeInfo(cc, SimpleConstOutput(Some(1)))
    )
  }

  it should "compute input pattern variables correctly" in {
    val a = m[Int]
    val bb = m[(Int, Int, Option[Int], (Int, Option[Int]))]
    val c = m[Unit]

    val result = go { case a(1|2) + c(()) + bb(p@(ytt, 1, None, (s, Some(t)))) => }
    result.info.inputs should matchPattern {
      case Array(
      InputMoleculeInfo(`a`, 0, OtherInputPattern(_, List()), _),
      InputMoleculeInfo(`c`, 1, Wildcard, _),
      InputMoleculeInfo(`bb`, 2, OtherInputPattern(_, List('p, 'ytt, 's, 't)), _)
      ) =>
    }
    result.info.toString shouldEqual "a(?) + bb(?p,ytt,s,t) + c(_) => "
  }

  it should "create partial functions for matching from reaction body" in {
    val aa = m[Option[Int]]
    val bb = m[(Int, Option[Int])]

    val result = go { case aa(Some(x)) + bb((0, None)) => aa(Some(x + 1)) }

    result.info.outputs shouldEqual List(OutputMoleculeInfo(aa, OtherOutputPattern))

    val pat_aa = result.info.inputs.head
    pat_aa.molecule shouldEqual aa
    val pat_bb = result.info.inputs(1)
    pat_bb.molecule shouldEqual bb

    (pat_aa.flag match {
      case OtherInputPattern(matcher, vars) =>
        matcher.isDefinedAt(Some(1)) shouldEqual true
        matcher.isDefinedAt(None) shouldEqual false
        vars shouldEqual List('x)
        true
      case _ => false
    }) shouldEqual true

    pat_bb.flag shouldEqual SimpleConst( (0, None) )
  }

  behavior of "output value computation"

  it should "not fail to compute outputs for an inline reaction" in {
    val thrown = intercept[Exception] {
      val a = m[Int]
      site(
        go { case a(1) => a(1) }
      )
      a.consumingReactions.get.map(_.info.outputs) shouldEqual Set(List(OutputMoleculeInfo(a, SimpleConstOutput(1))))
    }
    thrown.getMessage shouldEqual "In Site{a => ...}: Unavoidable livelock: reaction {a(1) => a(1)}"
  }

  it should "compute inputs and outputs correctly for an inline nested reaction" in {
    val a = m[Int]
    site(
      go {
        case a(1) =>
          val c = m[Int]
          site(go { case c(_) => })
          c(2)
          a(2)
      }
    )
    a.consumingReactions.get.size shouldEqual 1
    a.emittingReactions.size shouldEqual 1
    a.consumingReactions.get.map(_.info.outputs).head shouldEqual List(OutputMoleculeInfo(a, SimpleConstOutput(2)))
    a.consumingReactions.get.map(_.info.inputs).head shouldEqual List(InputMoleculeInfo(a, 0, SimpleConst(1), constantOneSha1))
    a.emittingReactions.map(_.info.outputs).head shouldEqual List(OutputMoleculeInfo(a, SimpleConstOutput(2)))
    a.emittingReactions.map(_.info.inputs).head shouldEqual List(InputMoleculeInfo(a, 0, SimpleConst(1), constantOneSha1))
  }

  it should "not fail to compute outputs correctly for an inline nested reaction" in {
    val thrown = intercept[Exception] {
      val a = m[Int]
      site(
        go {
          case a(1) =>
            val c = m[Int]
            site(go { case c(_) => })
            c(2)
            a(1)
        }
      )
    }
    thrown.getMessage shouldEqual "In Site{a => ...}: Unavoidable livelock: reaction {a(1) => a(1)}"
  }

  it should "compute outputs in the correct order for a reaction with no livelock" in {
    val a = m[Int]
    val b = m[Int]
    site(
      go { case a(2) => b(2); a(1); b(1) }
    )
    a.consumingReactions.get.size shouldEqual 1
    a.consumingReactions.get.map(_.info.outputs).head shouldEqual List(
      OutputMoleculeInfo(b, SimpleConstOutput(2)),
      OutputMoleculeInfo(a, SimpleConstOutput(1)),
      OutputMoleculeInfo(b, SimpleConstOutput(1))
    )
  }

  it should "correctly recognize nested emissions of non-blocking molecules" in {
    val a = m[Int]
    val c = m[Int]
    val d = m[Boolean]

    site(
      go { case a(x) + d(_) => c({
        a(1); 2
      })
      }
    )

    a.isBound shouldEqual true
    c.isBound shouldEqual false

    val reaction = a.consumingReactions.get.head
    c.emittingReactions.head shouldEqual reaction
    a.emittingReactions.head shouldEqual reaction

    reaction.info.inputs should matchPattern {
      case Array(InputMoleculeInfo(`a`, 0, SimpleVar('x, _), `simpleVarXSha1`), InputMoleculeInfo(`d`, 1, Wildcard, `wildcardSha1`)) =>
    }
    reaction.info.outputs shouldEqual List(OutputMoleculeInfo(a, SimpleConstOutput(1)), OutputMoleculeInfo(c, OtherOutputPattern))
  }

  it should "correctly recognize nested emissions of blocking molecules and reply values" in {
    val a = b[Int, Int]
    val c = m[Int]
    val d = m[Unit]

    site(
      go { case d(_) => c(a(1)) },
      go { case a(x, r) => d(r(x)) }
    )

    a.isBound shouldEqual true
    c.isBound shouldEqual false
    d.isBound shouldEqual true

    val reaction1 = d.consumingReactions.get.head
    a.emittingReactions.head shouldEqual reaction1
    c.emittingReactions.head shouldEqual reaction1

    val reaction2 = a.consumingReactions.get.head
    d.emittingReactions.head shouldEqual reaction2

    reaction1.info.inputs shouldEqual List(InputMoleculeInfo(d, 0, Wildcard, wildcardSha1))
    reaction1.info.outputs shouldEqual List(OutputMoleculeInfo(a, SimpleConstOutput(1)), OutputMoleculeInfo(c, OtherOutputPattern))

    reaction2.info.inputs should matchPattern {
      case Array(InputMoleculeInfo(`a`, 0, SimpleVar('x, _), `simpleVarXSha1`)) =>
    }
    reaction2.info.outputs shouldEqual List(OutputMoleculeInfo(d, OtherOutputPattern))
  }

  behavior of "auxiliary functions"

  it should "find expression trees for constant values" in {
    rawTree(1) shouldEqual "Literal(Constant(1))"
    rawTree(None) shouldEqual "Select(Ident(scala), scala.None)"

    (Set(
      "Apply(TypeApply(Select(Select(Ident(scala), scala.Some), TermName(\"apply\")), List(TypeTree())), List(Literal(Constant(1))))"
    ) contains rawTree(Some(1))) shouldEqual true
  }

  it should "find expression trees for matchers" in {
    rawTree(Some(1) match { case Some(1) => }) shouldEqual "Match(Apply(TypeApply(Select(Select(Ident(scala), scala.Some), TermName(\"apply\")), List(TypeTree())), List(Literal(Constant(1)))), List(CaseDef(Apply(TypeTree().setOriginal(Select(Ident(scala), scala.Some)), List(Literal(Constant(1)))), EmptyTree, Literal(Constant(())))))"
  }

  it should "find enclosing symbol names with correct scopes" in {
    val x = getName
    x shouldEqual "x"

    val y = {
      val z = getName
      (z, getName)
    }
    y shouldEqual (("z", "y"))

    val (y1, y2) = {
      val z = getName
      (z, getName)
    }
    y1 shouldEqual "z"
    y2 should fullyMatch regex "x\\$[0-9]+"
  }

  it should "refuse to emit singleton from non-reaction thread" in {
    val dIncorrectSingleton = m[Unit]
    val e = m[Unit]

    val r1 = go { case dIncorrectSingleton(_) + e(_) => dIncorrectSingleton(); 123 }

    site(tp0)(
      r1,
      go { case _ => dIncorrectSingleton() }
    )

    val inputs = new InputMoleculeList(2)
    inputs(0) = (dIncorrectSingleton, MolValue(()))
    inputs(1) = (e, MolValue(()))
    r1.body.apply(inputs) shouldEqual 123 // Reaction ran and attempted to emit the singleton.
    waitSome()
    globalErrorLog.exists(_.contains(s"In Site{${dIncorrectSingleton.name} + ${e.name} => ...}: Refusing to emit singleton ${dIncorrectSingleton.name}() because this thread does not run a chemical reaction")) shouldEqual true
    e.logSoup shouldEqual s"Site{${dIncorrectSingleton.name} + ${e.name} => ...}\nMolecules: ${dIncorrectSingleton.name}()"
  }

  it should "refuse to emit singleton from a reaction that did not consume it when this cannot be determined statically" in {
    val c = m[Unit]
    val dIncorrectSingleton = m[Unit]
    val e = m[E]

    site(tp0)(
      go { case e(s) => s() },
      go { case dIncorrectSingleton(_) + c(_) => dIncorrectSingleton() },
      go { case _ => dIncorrectSingleton() }
    )

    e(dIncorrectSingleton)
    waitSome()
    e.logSoup shouldEqual s"Site{c + ${dIncorrectSingleton.name} => ...; ${e.name} => ...}\nMolecules: ${dIncorrectSingleton.name}()"
    globalErrorLog.exists(_.contains(s"In Site{c + ${dIncorrectSingleton.name} => ...; ${e.name} => ...}: Refusing to emit singleton ${dIncorrectSingleton.name}() because this reaction {${e.name}(s) => } does not consume it")) shouldEqual true
  }

}
