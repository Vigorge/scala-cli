package scala.build.preprocessing

import fastparse.NoWhitespace.noWhitespaceImplicit
import fastparse._

import scala.build.preprocessing.directives.Directive

object TemporaryDirectivesParser {

  private def ws[_: P]        = P(" ".rep(1))
  private def nl[_: P]        = P(("\r".? ~ "\n").rep(1))
  private def emptyLine[_: P] = P(ws.rep() ~ nl)

  private def singleLineComment[_: P] =
    P(ws.rep() ~ !("//" ~ ws ~ "require") ~ !("//" ~ ws ~ "using") ~ "//" ~ P(CharPred(c =>
      c != '\n'
    )).rep() ~ nl)
      .map(_ => ())

  private def directive[_: P] = {
    def sc = P(";")
    def tpe = {
      def commentedUsingTpe = P("//" ~ ws ~ "using")
        .map(_ => (Directive.Using: Directive.Type, true))
      def usingKeywordTpe = P("using")
        .map(_ => (Directive.Using: Directive.Type, false))
      def usingTpe = P(ws.? ~ (commentedUsingTpe | usingKeywordTpe) ~ !(ws ~ "target"))
      def commentedRequireTpe = P("//" ~ ws ~ "require")
        .map(_ => (Directive.Require: Directive.Type, true))
      def requireKeywordTpe = P("require")
        .map(_ => (Directive.Require: Directive.Type, false))
      def commentedUsingTargetTpe = P("//" ~ ws ~ "using" ~ ws ~ "target")
        .map(_ => (Directive.Require: Directive.Type, true))
      def usingTargetKeywordTpe = P("using" ~ ws ~ "target")
        .map(_ => (Directive.Require: Directive.Type, false))
      def requireTpe = P(
        ws.? ~ (commentedRequireTpe | requireKeywordTpe | commentedUsingTargetTpe | usingTargetKeywordTpe)
      )
      P(usingTpe | requireTpe)
    }

    def simpleElemChar = P(
      CharPred(c => !c.isWhitespace && c != '"' && c != ',') |
        P("\\\\").map(_ => "\\") |
        P("\\\"").map(_ => "\"") |
        P("\\,").map(_ => ",")
    )
    def simpleElem = P(simpleElemChar.rep(1).!)
    def charInQuote = P(
      CharPred(c => c != '"' && c != '\\').! |
        P("\\\\").map(_ => "\\") |
        P("\\\"").map(_ => "\"")
    )
    def quotedElem = P("\"" ~ charInQuote.rep ~ "\"").map(_.mkString)
    def elem       = P(!("in" ~ (ws | "," | nl)) ~ (simpleElem | quotedElem))

    def parser = P(
      tpe ~ ws ~
        (elem.rep(1, sep = P(ws)) ~ (ws ~ "in" ~ ws ~ elem).?).rep(1, sep = P(ws.? ~ "," ~ ws.?)) ~
        nl.? ~ sc.? ~
        nl.?
    )

    parser.map {
      case (tpe0, isComment, allElems) =>
        allElems.map {
          case (elems, scopeOpt) =>
            Directive(tpe0, elems, scopeOpt, isComment)
        }
    }
  }

  private def maybeDirective[_: P] =
    // TODO Use some cuts above to also catch malformed directives?
    P((emptyLine | singleLineComment).rep() ~ directive.?)

  private def parseDirective(content: String, fromIndex: Int): Option[(Seq[Directive], Int)] = {
    // TODO Don't create a new String here
    val res = parse(content.drop(fromIndex), maybeDirective(_))
    res.fold((err, _, _) => sys.error(err), (dirOpt, idx) => dirOpt.map((_, idx + fromIndex)))
  }

  def parseDirectives(content: String): Option[(List[Directive], Option[String])] = {

    def helper(fromIndex: Int, acc: List[Directive]): (List[Directive], Int) =
      parseDirective(content, fromIndex) match {
        case None                  => (acc.reverse, fromIndex)
        case Some((dir, newIndex)) => helper(newIndex, dir.toList ::: acc)
      }

    val (directives, codeStartsAt) = helper(0, Nil)

    if (codeStartsAt == 0) {
      assert(directives.isEmpty)
      None
    }
    else {
      val onlyCommentedDirectives = directives.forall(_.isComment)
      val updatedContentOpt =
        if (onlyCommentedDirectives) None
        else
          Some {
            content.take(codeStartsAt).map(c => if (c.isControl) c else ' ') ++
              content.drop(codeStartsAt)
          }
      Some((directives, updatedContentOpt))
    }
  }

}