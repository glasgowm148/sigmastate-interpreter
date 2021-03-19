package sigmastate

import gf2t.GF2_192_Poly
import org.bouncycastle.util.BigIntegers
import scalan.Nullable
import sigmastate.Values.SigmaBoolean
import sigmastate.basics.DLogProtocol.{ProveDlog, SecondDLogProverMessage}
import sigmastate.basics.VerifierMessage.Challenge
import sigmastate.basics.{ProveDHTuple, SecondDiffieHellmanTupleProverMessage}
import sigmastate.interpreter.CryptoConstants
import sigmastate.lang.exceptions.SerializerException
import sigmastate.serialization.SigmaSerializer
import sigmastate.utils.{Helpers, SigmaByteReader, SigmaByteWriter}
import spire.syntax.all.cfor

object SigSerializer {

  val hashSize = CryptoConstants.soundnessBits / 8
  val order = CryptoConstants.groupSize

  /** Recursively traverses the given node and serializes challenges and prover messages
    * to the given writer.
    * Note, sigma propositions and commitments are not serialized.
    *
    * @param tree tree to traverse and serialize
    * @return the proof bytes containing all the serialized challenges and prover messages
    *         (aka `z` values)
    */
  def toProofBytes(tree: UncheckedTree): Array[Byte] = {
    tree match {
      case NoProof =>
        Array.emptyByteArray
      case t: UncheckedSigmaTree =>
        val w = SigmaSerializer.startWriter()
        toProofBytes(t, w, writeChallenge = true)
        val res = w.toBytes
        res
    }
  }

  /** Recursively traverses the given node and serializes challenges and prover messages
    * to the given writer.
    * Note, sigma propositions and commitments are not serialized.
    *
    * @param node           subtree to traverse
    * @param w              writer to put the bytes
    * @param writeChallenge if true, than node.challenge is serialized, and omitted
    *                       otherwise.
    */
  def toProofBytes(node: UncheckedSigmaTree,
                   w: SigmaByteWriter,
                   writeChallenge: Boolean): Unit = {
    if (writeChallenge) {
      w.putBytes(node.challenge)
    }
    node match {
      case dl: UncheckedSchnorr =>
        val z = BigIntegers.asUnsignedByteArray(order, dl.secondMessage.z.bigInteger)
        w.putBytes(z)

      case dh: UncheckedDiffieHellmanTuple =>
        val z = BigIntegers.asUnsignedByteArray(order, dh.secondMessage.z)
        w.putBytes(z)

      case and: CAndUncheckedNode =>
        // don't write children's challenges -- they are equal to the challenge of this node
        val cs = and.children.toArray
        cfor(0)(_ < cs.length, _ + 1) { i =>
          val child = cs(i)
          toProofBytes(child, w, writeChallenge = false)
        }

      case or: COrUncheckedNode =>
        // don't write last child's challenge -- it's computed by the verifier via XOR
        val cs = or.children.toArray
        val iLastChild = cs.length - 1
        cfor(0)(_ < iLastChild, _ + 1) { i =>
          val child = cs(i)
          toProofBytes(child, w, writeChallenge = true)
        }
        toProofBytes(cs(iLastChild), w, writeChallenge = false)

      case t: CThresholdUncheckedNode =>
        // write the polynomial, except the zero coefficient
        val poly = t.polynomialOpt.get.toByteArray(false)
        w.putBytes(poly)

        // don't write children's challenges
        val cs = t.children.toArray
        cfor(0)(_ < cs.length, _ + 1) { i =>
          val child = cs(i)
          toProofBytes(child, w, writeChallenge = false)
        }

      case _ =>
        throw new SerializerException(s"Don't know how to execute toBytes($node)")
    }
  }

  /** Verifier Step 2: In a top-down traversal of the tree, obtain the challenges for the
    * children of every non-leaf node by reading them from the proof or computing them.
    * Verifier Step 3: For every leaf node, read the response z provided in the proof.
    *
    * @param exp   sigma proposition which defines the structure of bytes from the reader
    * @param proof proof to extract challenges from
    * @return An instance of [[UncheckedTree]] i.e. either [[NoProof]] or [[UncheckedSigmaTree]]
    */
  def parseAndComputeChallenges(exp: SigmaBoolean, proof: Array[Byte]): UncheckedTree = {
    if (proof.isEmpty)
      NoProof
    else {
      // Verifier step 1: Read the root challenge from the proof.
      val r = SigmaSerializer.startReader(proof)
      val res = parseAndComputeChallenges(exp, r, Nullable.None)
      res
    }
  }

  /** Verifier Step 2: In a top-down traversal of the tree, obtain the challenges for the
    * children of every non-leaf node by reading them from the proof or computing them.
    * Verifier Step 3: For every leaf node, read the response z provided in the proof.
    *
    * @param exp          sigma proposition which defines the structure of bytes from the reader
    * @param r            reader to extract challenges from
    * @param challengeOpt if non-empty, then the challenge has been computed for this node
    *                     by its parent; else it needs to be read from the proof (via reader)
    * @return An instance of [[UncheckedSigmaTree]]
    *
    * HOTSPOT: don't beautify the code
    * Note, Nullable is used instead of Option to avoid allocations.
    */
  def parseAndComputeChallenges(
        exp: SigmaBoolean,
        r: SigmaByteReader,
        challengeOpt: Nullable[Challenge] = Nullable.None): UncheckedSigmaTree = {
    // Verifier Step 2: Let e_0 be the challenge in the node here (e_0 is called "challenge" in the code)
    val challenge = if (challengeOpt.isEmpty) {
      Challenge @@ r.getBytes(hashSize)
    } else {
      challengeOpt.get
    }

    exp match {
      case dl: ProveDlog =>
        // Verifier Step 3: For every leaf node, read the response z provided in the proof.
        val z = BigIntegers.fromUnsignedByteArray(r.getBytes(order))
        UncheckedSchnorr(dl, None, challenge, SecondDLogProverMessage(z))

      case dh: ProveDHTuple =>
        // Verifier Step 3: For every leaf node, read the response z provided in the proof.
        val z = BigIntegers.fromUnsignedByteArray(r.getBytes(order))
        UncheckedDiffieHellmanTuple(dh, None, challenge, SecondDiffieHellmanTupleProverMessage(z))

      case and: CAND =>
        // Verifier Step 2: If the node is AND, then all of its children get e_0 as the challenge
        val nChildren = and.children.length
        val children = new Array[UncheckedSigmaTree](nChildren)
        cfor(0)(_ < nChildren, _ + 1) { i =>
          children(i) = parseAndComputeChallenges(and.children(i), r, Nullable(challenge))
        }
        CAndUncheckedNode(challenge, children)

      case or: COR =>
        // Verifier Step 2: If the node is OR, then each of its children except rightmost
        // one gets the challenge given in the proof for that node.
        // The rightmost child gets a challenge computed as an XOR of the challenges of all the other children and e_0.

        // Read all the children but the last and compute the XOR of all the challenges including e_0
        val nChildren = or.children.length
        val children = new Array[UncheckedSigmaTree](nChildren)
        val xorBuf = challenge.clone()
        val iLastChild = nChildren - 1
        cfor(0)(_ < iLastChild, _ + 1) { i =>
          val parsedChild = parseAndComputeChallenges(or.children(i), r, Nullable.None)
          children(i) = parsedChild
          Helpers.xorU(xorBuf, parsedChild.challenge) // xor it into buffer
        }
        val lastChild = or.children(iLastChild)

        // use the computed XOR for last child's challenge
        children(iLastChild) = parseAndComputeChallenges(
          lastChild, r, challengeOpt = Nullable(Challenge @@ xorBuf))

        COrUncheckedNode(challenge, children)

      case t: CTHRESHOLD =>
        // Verifier Step 2: If the node is THRESHOLD,
        // evaluate the polynomial Q(x) at points 1, 2, ..., n to get challenges for child 1, 2, ..., n, respectively.

        // Read the polynomial -- it has n-k coefficients
        val nChildren = t.children.length
        val coeffBytes = r.getBytes(hashSize * (nChildren - t.k))
        val polynomial = GF2_192_Poly.fromByteArray(challenge, coeffBytes)

        val children = new Array[UncheckedSigmaTree](nChildren)
        cfor(0)(_ < nChildren, _ + 1) { i =>
          val c = Challenge @@ polynomial.evaluate((i + 1).toByte).toByteArray
          children(i) = parseAndComputeChallenges(t.children(i), r, Nullable(c))
        }

        // Verifier doesn't need the polynomial anymore -- hence pass in None
        CThresholdUncheckedNode(challenge, children, t.k, Some(polynomial))
    }
  }

}