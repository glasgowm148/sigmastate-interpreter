package sigmastate.crypto

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport
import scala.scalajs.js.typedarray.Uint8Array

@js.native
@JSImport("sigmajs-crypto-facade", "CryptoContext")
class CryptoContextJs() extends js.Object {
  def getModulus(): js.BigInt = js.native
  def getOrder(): js.BigInt = js.native
  def validatePoint(x: js.BigInt, y: js.BigInt): Platform.Point = js.native
  def getInfinity(): Platform.Point = js.native
  def decodePoint(encoded: String): Platform.Point = js.native
  def getGenerator(): Platform.Point = js.native
}

@js.native
@JSImport("sigmajs-crypto-facade", "CryptoFacade")
object CryptoFacadeJs extends js.Object {
  def normalizePoint(point: Platform.Point): Platform.Point = js.native

  def createCryptoContext(): CryptoContextJs = js.native

  def negatePoint(point: Platform.Point): Platform.Point = js.native 

  def isInfinityPoint(point: Platform.Point): Boolean = js.native 

  def multiplyPoint(point: Platform.Point, scalar: js.BigInt): Platform.Point = js.native

  def addPoint(point1: Platform.Point, point2: Platform.Point): Platform.Point = js.native

  def showPoint(point: Platform.Point): String = js.native

  def testBitZeroOfFieldElem(element: js.BigInt): Boolean = js.native

  def getEncodedOfFieldElem(element: js.BigInt): Uint8Array = js.native

  def getXCoord(point: Platform.Point): js.BigInt = js.native

  def getYCoord(point: Platform.Point): js.BigInt = js.native

  def getAffineXCoord(point: Platform.Point): js.BigInt = js.native

  def getAffineYCoord(point: Platform.Point): js.BigInt = js.native

  def hashHmacSHA512(key: Uint8Array, data: Uint8Array): Uint8Array = js.native

  def generatePbkdf2Key(normalizedMnemonic: String, normalizedPass: String): Uint8Array = js.native
}

@js.native
@JSImport("sigmajs-crypto-facade", "Point")
object Point extends js.Any {
  def fromHex(hex: String): Platform.Point = js.native
  def ZERO: Platform.Point = js.native
}

@js.native
@JSImport("sigmajs-crypto-facade", "utils")
object utils extends js.Any {
  def bytesToHex(bytes: Uint8Array): String = js.native
  def hexToBytes(hex: String): Uint8Array = js.native
}