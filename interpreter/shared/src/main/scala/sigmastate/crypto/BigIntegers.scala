package sigmastate.crypto

import java.math.BigInteger
import scala.util.Random

object BigIntegers {

  val ZERO: BigInteger = BigInteger.valueOf(0)

  private val MAX_ITERATIONS = 1000

  @throws[IllegalArgumentException]
  def createRandom(bitLength: Int, random: Random) = {
    if (bitLength < 1) throw new IllegalArgumentException("bitLength must be at least 1")
    val nBytes = (bitLength + 7) / 8
    val rv = new Array[Byte](nBytes)
    random.nextBytes(rv)

    // strip off any excess bits in the MSB
    val xBits = 8 * nBytes - bitLength
    rv(0) = (rv(0) & 255 >>> xBits).toByte
    rv
  }

  /**
    * Return a positive BigInteger in the range of 0 to 2**bitLength - 1.
    *
    * @param bitLength maximum bit length for the generated BigInteger.
    * @param random    a source of randomness.
    * @return a positive BigInteger
    */
  def createRandomBigInteger(
      bitLength: Int,
      random: Random): BigInteger = {
    new BigInteger(1, createRandom(bitLength, random))
  }

  /**
    * Return a random BigInteger not less than 'min' and not greater than 'max'
    *
    * @param min    the least value that may be generated
    * @param max    the greatest value that may be generated
    * @param random the source of randomness
    * @return a random BigInteger value in the range [min,max]
    */
  def createRandomInRange(
      min: BigInteger,
      max: BigInteger,
      random: Random): BigInteger = {
    val cmp = min.compareTo(max)
    if (cmp >= 0) {
      if (cmp > 0) throw new IllegalArgumentException("'min' may not be greater than 'max'")
      return min
    }

    if (min.bitLength > max.bitLength / 2)
      return createRandomInRange(ZERO, max.subtract(min), random).add(min)

    for ( i <- 0 until MAX_ITERATIONS ) {
      val x = createRandomBigInteger(max.bitLength, random)
      if (x.compareTo(min) >= 0 && x.compareTo(max) <= 0) return x
    }
    // fall back to a faster (restricted) method
    createRandomBigInteger(max.subtract(min).bitLength - 1, random).add(min)
  }

  /**
    * Return the passed in value as an unsigned byte array of the specified length, padded with
    * leading zeros as necessary..
    *
    * @param length the fixed length of the result
    * @param value  the value to be converted.
    * @return a byte array padded to a fixed length with leading zeros.
    */
  def asUnsignedByteArray(length: Int, value: BigInteger): Array[Byte] = {
    val bytes = value.toByteArray
    if (bytes.length == length) return bytes
    val start = if (bytes(0) == 0) 1 else 0

    val count = bytes.length - start
    if (count > length)
      throw new IllegalArgumentException("standard length exceeded for value")

    val tmp = new Array[Byte](length)
    System.arraycopy(bytes, start, tmp, tmp.length - count, count)
    tmp
  }

  /**
    * Return the passed in value as an unsigned byte array.
    *
    * @param value the value to be converted.
    * @return a byte array without a leading zero byte if present in the signed encoding.
    */
  def asUnsignedByteArray(value: BigInteger): Array[Byte] = {
    val bytes = value.toByteArray
    if (bytes(0) == 0) {
      val tmp = new Array[Byte](bytes.length - 1)
      System.arraycopy(bytes, 1, tmp, 0, tmp.length)
      return tmp
    }
    bytes
  }

  def fromUnsignedByteArray(buf: Array[Byte]) = new BigInteger(1, buf)
}
