package sigmastate.eval

import scala.util.Success
import sigmastate.{SInt, SLong, SType}
import sigmastate.Values.{Constant, SValue, IntConstant, LongConstant}
import org.ergoplatform.{ErgoLikeContext, ErgoBox}
import sigmastate.utxo.CostTable
import special.sigma.{TestBox, ContractsTestkit, AnyValue, Box, TestContext => TContext, SigmaContract => SContract, Context => VContext}

import scalan.BaseCtxTests
import sigmastate.lang.TransformingSigmaBuilder
import org.scalatest.TryValues._

trait ErgoScriptTestkit extends ContractsTestkit { self: BaseCtxTests =>

  lazy val IR = new TestContext with Evaluation {
    import TestSigmaDslBuilder._

    val sigmaDslBuilder = RTestSigmaDslBuilder()
    val builder = TransformingSigmaBuilder
    beginPass(new DefaultPass("mypass",
      Pass.defaultPassConfig.copy(constantPropagation = false)))

    val sigmaDslBuilderValue = new special.sigma.TestSigmaDslBuilder()
    val costedBuilderValue = new special.collection.ConcreteCostedBuilder()
    val monoidBuilderValue = new special.collection.MonoidBuilderInst()
  }

  type EsEnv = Map[String, Any]

  val noEnv: EsEnv = Map()
  val AliceId = Array[Byte](1) // 0x0001
  def newAliceBox(id: Byte, value: Long) = new TestBox(
    Cols.fromArray(Array[Byte](0, id)), value, Cols.fromArray(AliceId), noBytes, noBytes, noRegisters)

  def newContext(height: Long, self: Box, vars: AnyValue*): TContext = {
    new TContext(noInputs, noOutputs, height, self, emptyAvlTree, vars.toArray)
  }
  implicit class TContextOps(ctx: TContext) {
    def withInputs(inputs: Box*) = new TContext(inputs.toArray, ctx.outputs, ctx.height, ctx.selfBox, emptyAvlTree, ctx.vars)
    def withOutputs(outputs: Box*) = new TContext(ctx.inputs, outputs.toArray, ctx.height, ctx.selfBox, emptyAvlTree, ctx.vars)
  }

  val boxA1 = newAliceBox(1, 100)
  val boxA2 = newAliceBox(2, 200)

  implicit class ErgoBoxOps(ebox: ErgoBox) {
    def toTestBox: Box = {
      val rs = regs(ebox.additionalRegisters.map { case (k,v) => (k.number -> v) })
      new TestBox(Cols.fromArray(ebox.id), ebox.value, Cols.fromArray(ebox.propositionBytes), noBytes, noBytes, rs)
    }
  }

  implicit class ErgoLikeContextOps(ergoCtx: ErgoLikeContext) {
    def toTestContext: TContext = {
      val inputs = ergoCtx.boxesToSpend.toArray.map(_.toTestBox)
      val outputs = ergoCtx.spendingTransaction.outputs.toArray.map(_.toTestBox)
      val vars = contextVars(ergoCtx.extension.values)
      new TContext(inputs, outputs, ergoCtx.currentHeight, ergoCtx.self.toTestBox, emptyAvlTree, vars.arr)
    }
  }

  def contract(canOpen: VContext => Boolean) = new NoEnvContract(canOpen)

  import IR._
  import Context._
  case class Result[+T](calc: Option[T], cost: Option[Int], size: Option[Long])
  object Result {
    def apply[T](calc: T): Result[T] = Result[T](Some(calc), None, None)
    def apply[T](calc: T, cost: Int, size: Long): Result[T] = Result[T](Some(calc), Some(cost), Some(size))
  }
  def NoResult[T] = Result[T](None, None, None)
  case class EsTestCase[T](
      name: String,  // name of the test case, used in forming file names in test-out directory
      env: EsEnv,
      script: String,
      ctx: Option[VContext] = None,
      testContract: Option[VContext => T] = None,
      expectedCalc: Option[Rep[Context] => Rep[T]] = None,
      expectedCost: Option[Rep[Context] => Rep[Int]] = None,
      expectedSize: Option[Rep[Context] => Rep[Long]] = None,
      expectedTree: Option[SValue] = None,
      expectedResult: Result[T] = NoResult,
      printGraphs: Boolean = true)
  {
    lazy val expectedCalcF = expectedCalc.map(fun(_))
    lazy val expectedCostF = expectedCost.map(fun(_))
    lazy val expectedSizeF = expectedSize.map(fun(_))

    def checkExpected[T](x: T, expected: Option[T]) = {
      if (expected.isDefined)
        x shouldBe expected.get
    }

    def pairify(xs: Seq[Sym]): Sym = xs match {
      case Seq(x) => x
      case Seq(a, b) => Pair(a, b)
      case _ => Pair(xs.head, pairify(xs.tail))
    }

    def doCosting: Rep[(Context => T, (Context => Int, Context => Long))] = {
      val costed = cost(env, script)
      val res @ Tuple(calcF, costF, sizeF) = split(costed.asRep[Context => Costed[T]])
      if (printGraphs) {
        val str = struct("calc" -> calcF, "cost" -> costF, "size" -> sizeF)
        val strExp = struct(
          expectedCalcF.map("calc" -> _).toSeq ++
          expectedCostF.map("cost" -> _).toSeq ++
          expectedSizeF.map("size" -> _).toSeq)
        val graphs = Seq(str, strExp)
        emit(name, graphs:_*)
      }
      checkExpected(calcF, expectedCalcF)
      checkExpected(costF, expectedCostF)
      checkExpected(sizeF, expectedSizeF)
      res
    }

    def doReduce(): Unit = {
      val Tuple(calcF, costF, sizeF) = doCosting
      verifyCostFunc(costF) shouldBe Success(())
      verifyIsValid(calcF) shouldBe Success(())
      if (ctx.isDefined) {
        val testContractRes = testContract.map(_(ctx.get))
        testContractRes.foreach { res =>
          checkExpected(res, expectedResult.calc)
        }

        // check cost
        val costFun = IR.compile[SInt.type](getDataEnv, costF)
        val IntConstant(estimatedCost) = costFun(ctx.get)
        checkExpected(estimatedCost, expectedResult.cost)
        (estimatedCost < CostTable.ScriptLimit) shouldBe true

        // check size
        val sizeFun = IR.compile[SLong.type](getDataEnv, sizeF)
        val LongConstant(estimatedSize) = sizeFun(ctx.get)
        checkExpected(estimatedSize, expectedResult.size)

        // check calc
        val valueFun = IR.compile[SType](getDataEnv, calcF.asRep[Context => SType#WrappedType])
        val Constant(res: T @unchecked, _) = valueFun(ctx.get)
        checkExpected(res, expectedResult.calc)
      }
    }
  }

  def Case[T](env: EsEnv, name: String, script: String, ctx: VContext,
      contract: VContext => T,
      calc: Rep[Context] => Rep[T],
      cost: Rep[Context] => Rep[Int],
      size: Rep[Context] => Rep[Long],
      tree: SValue,
      result: Result[T]) =
    EsTestCase[T](name, env, script, Option(ctx), Option(contract),
      Option(calc), Option(cost), Option(size),
      Option(tree), result)

  def checkAll[T](env: EsEnv, name: String, script: String, ctx: VContext,
      contract: VContext => T,
      calc: Rep[Context] => Rep[T],
      cost: Rep[Context] => Rep[Int],
      size: Rep[Context] => Rep[Long],
      tree: SValue,
      result: Result[T]): Unit =
  {
    val tcase = Case[T](env, name, script, ctx, contract, calc, cost, size, tree, result)
    tcase.doReduce()
  }

  def checkInEnv[T](env: EsEnv, name: String, script: String,
      expectedCalc: Rep[Context] => Rep[T],
      expectedCost: Rep[Context] => Rep[Int] = null,
      expectedSize: Rep[Context] => Rep[Long] = null
  ): Rep[(Context => T, (Context => Int, Context => Long))] =
  {
    val tc = EsTestCase[T](name, env, script, None, None,
      Option(expectedCalc),
      Option(expectedCost),
      Option(expectedSize), expectedTree = None, expectedResult = NoResult, printGraphs = true )
    val res = tc.doCosting
    res
  }

  def check[T](name: String, script: String,
      expectedCalc: Rep[Context] => Rep[T],
      expectedCost: Rep[Context] => Rep[Int] = null,
      expectedSize: Rep[Context] => Rep[Long] = null
      ): Rep[(Context => T, (Context => Int, Context => Long))] =
  {
    checkInEnv(Map(), name, script, expectedCalc, expectedCost, expectedSize)
  }

  def reduce(env: EsEnv, name: String, script: String, ctx: VContext, expectedResult: Any): Unit = {
    val tcase = EsTestCase[SType#WrappedType](name, env, script, Some(ctx), expectedResult = Result(expectedResult.asInstanceOf[SType#WrappedType]))
    tcase.doReduce()
  }

  def build(env: Map[String, Any], name: String, script: String, expected: SValue): Unit = {
    val costed = cost(env, script)
    val Tuple(valueF, costF, sizeF) = split(costed)
    emit(name, valueF, costF, sizeF)
    verifyCostFunc(costF) shouldBe(Success(()))
    verifyIsValid(valueF) shouldBe(Success(()))
    IR.buildTree(valueF) shouldBe expected
  }

}
