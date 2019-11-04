package sigmastate.verification.contract

import org.ergoplatform.ErgoLikeContext
import sigmastate.AvlTreeData
import sigmastate.compiler.macros.impl.{ErgoContract, ErgoContractCompiler}
import sigmastate.verification.SigmaDsl.api.sigma.{Context => VerifiedContext}
import stainless.annotation.ignore

@ignore
object DummyContractCompilation {

  def main(args: Array[String]): Unit = {
    val c = DummyContractVerification.contractInstance(10)
    println(c)
  }

//  def dummyContractInstance(limit: Int): ErgoContract =
//    ErgoContractCompiler.compile { ctx: VerifiedContext =>
//      DummyContractVerification.contract(ctx, limit)
//    }
}



