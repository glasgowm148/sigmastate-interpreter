package org.ergoplatform.sdk.js

import scala.scalajs.js
import scala.scalajs.js.annotation.JSExportTopLevel

@JSExportTopLevel("PreHeader")
class PreHeader(
    val version: Byte,
    val parentId: String,
    val timestamp: js.BigInt,
    val nBits: js.BigInt,
    val height: Int,
    val minerPk: String,
    val votes: String
) extends js.Object
