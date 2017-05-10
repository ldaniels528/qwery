package com.github.ldaniels528.qwery

import com.github.ldaniels528.qwery.SyntaxException._

/**
  * Syntax Exception
  * @author lawrence.daniels@gmail.com
  */
class SyntaxException(message: String, token: Token = null, cause: Throwable = null)
  extends RuntimeException(formatMessage(message, token), cause)

/**
  * Syntax Exception
  * @author lawrence.daniels@gmail.com
  */
object SyntaxException {

  private def formatMessage(message: String, token: Token) = {
    Option(token).map(t => s"$message near '${token.text}' at ${token.start}") getOrElse message
  }

}