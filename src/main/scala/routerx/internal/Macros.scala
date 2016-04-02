package routerx.internal

import upickle.default._
import routerx.{VolatileLink, RouteTable, Router,fragment}
import scala.language.experimental.macros
import scala.reflect.macros._

object Macros {

  def getCompanion(c: blackbox.Context)(tpe: c.Type) = {
    import c.universe._
    val symTab = c.universe.asInstanceOf[reflect.internal.SymbolTable]
    val pre = tpe.asInstanceOf[symTab.Type].prefix.asInstanceOf[Type]
    c.universe.internal.gen.mkAttributedRef(pre, tpe.typeSymbol.companion)
  }

  def prefixForSym(c: blackbox.Context)(rootPart: String, sym: c.universe.ClassSymbol): String = {
    import c.universe._
    val fragmentOverride: Option[String] = sym.annotations.collectFirst {
      case frag if frag.tree.tpe <:< c.weakTypeOf[fragment] => {
        frag.tree match {
          case Apply(Select(_,_),List(Literal(Constant(x)))) => x.asInstanceOf[String]
          case _ => c.abort(c.enclosingPosition,"Fragment arg must be a single literal string!")
        }
      }
    }
    val result = rootPart + "/" + fragmentOverride.getOrElse {
      val fullNamePart = sym.fullName.drop(sym.fullName.lastIndexOf('.') + 1).dropRight("Screen".size)
      val nameFragments = fullNamePart.foldLeft(List.empty[String]) { case (acc,char) =>
        acc match {
          case all if char.isUpper => char.toString.toLowerCase :: all
          case x :: tail => x + char.toString :: tail
        }
      }
      nameFragments.reverse.mkString("-")
    }
    require(result.startsWith("/") && result.length > 1, s"Invalid prefix created! $result")
    require(!result.endsWith("/"),s"Invalid prefix ended in '/' $result!")
    result
  }

  private def urlForCaseObject(c: blackbox.Context)(rootPart: String, sym: c.universe.Symbol): c.Tree = {
    import c.universe._
    require(rootPart != "/","Sanity check..")
    cq"e: ${sym.asType} => ${prefixForSym(c)(rootPart, sym.asClass)}"
  }

  private def urlForCaseClass(c: blackbox.Context)(rootPart: String, sym: c.universe.Symbol): c.Tree = {
    import c.universe._
    val term = TermName("e")
    require(rootPart != "/","(2) Sanity check..")
    val toParts = sym.asType.info.decls.filter(_.asTerm.isAccessor).map { acc =>
      q"implicitly[UrlPart[${acc.asTerm}]].toParts($term.$acc)"
    }.toList
    if(toParts.nonEmpty) {
      cq"""$term: ${sym.asType} =>
        val allParts = List(..$toParts).flatten
        ${prefixForSym(c)(rootPart, sym.asClass)} + "/" + allParts.mkString("/")
      """ //"""
    } else {
      cq"""$term: ${sym.asType} =>
        ${prefixForSym(c)(rootPart, sym.asClass)}
      """ //"""
    }
  }

  private def urlRoot(c: blackbox.Context)(urlPrefix: String, baseScreenTraitFullName: String): String = {
    require(urlPrefix.startsWith("/") || urlPrefix.isEmpty, s"($baseScreenTraitFullName) $urlPrefix must start with '/'!")
    require(!urlPrefix.endsWith("/"),s"($baseScreenTraitFullName) $urlPrefix must not with '/'!")

    val rootSymbolName = baseScreenTraitFullName.split('.').toList.lastOption.getOrElse("Screen")
    if(!rootSymbolName.endsWith("Screen")) {
      c.abort(c.enclosingPosition,"Route traits and objects must end in 'Screen'")
    }

    val nameSize = rootSymbolName.length - "Screen".length

    s"$urlPrefix${if(nameSize > 0) "/" + rootSymbolName.take(nameSize).toLowerCase else ""}"
  }

  private def generateLinksToUrl(c: blackbox.Context)(urlPrefix: String, baseScreenTrait: c.universe.ClassSymbol): Set[c.universe.Tree] = {
    import c.universe._
    require(urlPrefix.startsWith("/") || urlPrefix.isEmpty, s"($baseScreenTrait) $urlPrefix must start with '/'!")
    require(!urlPrefix.endsWith("/"),s"($baseScreenTrait) $urlPrefix must not with '/'!")

    if(baseScreenTrait.knownDirectSubclasses.isEmpty) {
      c.abort(c.enclosingPosition, "Error with knownDirectSubclasses (SI-7046)")
    }

    val rootPart = urlRoot(c)(urlPrefix,baseScreenTrait.fullName)

    baseScreenTrait.knownDirectSubclasses.map { sym =>

      val isCaseObject = sym.asClass.isCaseClass && sym.asClass.isModuleClass
      val isCaseClass = sym.asClass.isCaseClass && !sym.asClass.isModuleClass
      val isSealedTrait = sym.asClass.isTrait && sym.asClass.isSealed
      val isSealedAbstract = sym.asClass.isAbstract && sym.asClass.isSealed && !sym.asClass.isTrait

      if (isCaseObject) urlForCaseObject(c)(rootPart, sym)
      else if (isCaseClass) urlForCaseClass(c)(rootPart, sym)
      else if (isSealedTrait)  {
        cq"e: ${sym.asType} => e match { case ..${generateLinksToUrl(c)(rootPart,sym.asClass)} }"
      }
      else if(isSealedAbstract) {
        val wurt = c.Expr[String](q""""TODO"""")
        cq"e: ${sym.asType} => { println($wurt) ; $wurt }"
      }
      else {
        c.abort(c.enclosingPosition, "Unknown Subclass Type!: " + sym)
      }
    }
  }

  private def extractAccessorParts(c: blackbox.Context)(rootPart: String, sym: c.universe.Symbol) = {
    import c.universe._
    val url = prefixForSym(c)(rootPart, sym.asClass)
    val companion = getCompanion(c)(sym.asType.toType)
    val unusedParts = TermName("unusedParts")
    val accessors = sym.asType.info.decls.filter(_.asTerm.isParamAccessor)
    println("Wat: " + sym)
    println(sym.asClass.info.decls.filter(t => t.isTerm).map(_.asTerm.isAccessor))
    println(sym.asClass.info.decls.filter(t => t.isTerm).map(_.asTerm.isMethod))
    println(sym.asClass.info.decls.filter(t => t.isTerm).map(_.asTerm.isParameter))
    println(sym.asClass.info.decls.filter(t => t.isTerm).map(_.asTerm.isConstructor))
    println(sym.asClass.info.decls.filter(t => t.isTerm).map(_.asTerm.isGetter))
    println(sym.asClass.info.decls.filter(t => t.isTerm).map(_.asTerm.isAbstract))
    println(sym.asClass.info.decls.filter(t => t.isTerm).map(_.asTerm.isByNameParam))
    println(sym.asClass.info.decls.filter(t => t.isTerm).map(_.asTerm.isClass))
    println(sym.asClass.info.decls.filter(t => t.isTerm).map(_.asTerm.isCaseAccessor))
    println(sym.asClass.info.decls.filter(t => t.isTerm).map(_.asTerm.isParamAccessor))
    println(sym.asClass.info.decls.filter(t => t.isTerm).map(_.asTerm.isOverloaded))
    println(sym.asClass.info.decls.filter(t => t.isTerm).map(_.asTerm.isAbstractOverride))
    println(sym.asClass.info.decls.map(a => showRaw(a)))
    println("GOT: " + accessors.mkString("\n"))
    accessors.map { acc =>
      val imp = q"implicitly[UrlPart[${acc.asTerm}]]"
      q"val ${TermName(acc.name.toString+"$")} = $imp"
    }
  }

  private def generateCaseClassUrlLinks(c: blackbox.Context)(rootPart: String, sym: c.universe.Symbol): c.universe.Tree = {
    import c.universe._
    val url = prefixForSym(c)(rootPart, sym.asClass)
    val companion = getCompanion(c)(sym.asType.toType)
    val unusedParts = TermName("unusedParts")
    val accessors = sym.asType.info.decls.filter(_.asTerm.isAccessor)
    val elemPartials = accessors.zipWithIndex.map { case (acc,idx) =>
      val accTerm = TermName(s"acc$idx")
      val imp = q"implicitly[UrlPart[${acc.asTerm}]]"
      fq"""$accTerm <- {
              val partial = $imp
              val r = partial.fromParts($unusedParts.take(partial.size))
              $unusedParts = $unusedParts.drop(partial.size)
              r
          }""" //"""
    }
    val accessorsTerms = (0 until accessors.size).map { idx => TermName(s"acc$idx") }
    val builder = q"""{ case ((remaining:String),(ec:ExecutionContext)) =>
          var $unusedParts = remaining.split('/').toList.drop(1)
          for (..$elemPartials) yield { $companion.apply(..$accessorsTerms) }
        }"""
    q"$url -> $builder"
  }

  private def generateUrlToLinks[Link: c.WeakTypeTag](c: blackbox.Context)(rootPart: String, baseScreenTrait: c.universe.ClassSymbol): Set[c.universe.Tree] = {
    import c.universe._

    val linkTpe = weakTypeTag[Link].tpe

    def sealedLikeSubtable(innerUrlMap: Set[Tree]): Tree = {
      val subtable = q"""{ case ((remaining:String),(ec:ExecutionContext)) =>
          val unurl: Map[String, (String,ExecutionContext) => Future[$linkTpe]] = Map(..$innerUrlMap)
          unurl
            .toList
            .sortBy(_._1)(implicitly[Ordering[String]].reverse)
            .find(part => remaining.startsWith(part._1))
            .map
          { case (matched,builder) =>
            builder(remaining.drop(matched.size),ec)
          }.get
        }"""
      subtable
    }

    baseScreenTrait.knownDirectSubclasses.map { sym =>

      if(!sym.fullName.endsWith("Screen")) {
        c.abort(c.enclosingPosition,s"All link types must end in Screen: $sym")
      }

      val isCaseObject = sym.asClass.isCaseClass && sym.asClass.isModuleClass
      val isCaseClass = sym.asClass.isCaseClass && !sym.asClass.isModuleClass
      val isSealedTrait = sym.asClass.isTrait && sym.asClass.isSealed
      val isSealedAbstract = sym.asClass.isAbstract && sym.asClass.isSealed && !sym.asClass.isTrait

      if(isCaseObject) {
        val url = prefixForSym(c)(rootPart, sym.asClass)
        val basicCaseObject = q"{ case ((_:String),(ec:ExecutionContext)) => Future.successful(${sym.asClass.module})}"
        q"$url -> $basicCaseObject"
      }

      else if(isSealedTrait) {
        val prefix = urlRoot(c)(rootPart,sym.fullName)
        val omg = generateUrlToLinks(c)("",sym.asClass)
        val subtable = sealedLikeSubtable(omg)
        q"""$prefix  -> $subtable"""
      }

      else if(isSealedAbstract) {
        println("LETS DO THIS!")
        val prefix = urlRoot(c)(rootPart,sym.fullName)
        val lolz = extractAccessorParts(c)(rootPart,sym)
        val omg = generateUrlToLinks(c)("",sym.asClass)
        val subtable = sealedLikeSubtable(omg)
        println("PREFIX IS " + prefix)
        val todo =
          q"""$prefix -> {
              ..$lolz
             $subtable
          }"""
        println("########################")
        println(todo)
        todo
      }

      else if(isCaseClass) {
//        val url = prefixForSym(c)(rootPart, sym.asClass)
//        val companion = getCompanion(c)(sym.asType.toType)
//        val unusedParts = TermName("unusedParts")
//        val accessors = sym.asType.info.decls.filter(_.asTerm.isAccessor)
//        val elemPartials = accessors.zipWithIndex.map { case (acc,idx) =>
//          val accTerm = TermName(s"acc$idx")
//          val imp = q"implicitly[UrlPart[${acc.asTerm}]]"
//          fq"""$accTerm <- {
//              val partial = $imp
//              val r = partial.fromParts($unusedParts.take(partial.size))
//              $unusedParts = $unusedParts.drop(partial.size)
//              r
//          }""" //"""
//        }
//        val accessorsTerms = (0 until accessors.size).map { idx => TermName(s"acc$idx") }
//        val builder = q"""{ case ((remaining:String),(ec:ExecutionContext)) =>
//          var $unusedParts = remaining.split('/').toList.drop(1)
//          for (..$elemPartials) yield { $companion.apply(..$accessorsTerms) }
//        }"""
//        q"$url -> $builder"
        generateCaseClassUrlLinks(c)(rootPart,sym)
      }

      else {
        c.abort(c.enclosingPosition,"Unknown Subclass Type!")
      }
    }
  }

  private def generateVolatileLinks(c: blackbox.Context)(baseScreenTrait: c.universe.ClassSymbol): Set[c.universe.Tree] = {
    import c.universe._
    val volatileLinkTpe = weakTypeTag[VolatileLink].tpe
    baseScreenTrait.knownDirectSubclasses.flatMap { sym =>
      val isSealedTrait = sym.asClass.isTrait && sym.asClass.isSealed
      if(sym.asClass.baseClasses.exists(_.asType.toType <:< volatileLinkTpe)) {
        List(cq"_: ${sym.asType} => true")
      }
      else if(isSealedTrait) {
        generateVolatileLinks(c)(sym.asClass)
      }
      else List.empty
    }
  }

  def generateRouterTable[Link: c.WeakTypeTag](c: blackbox.Context): c.Expr[RouteTable[Link]] = {
    import c.universe._
    val linkTpe = weakTypeTag[Link].tpe

    if(!linkTpe.typeSymbol.asClass.isTrait || !linkTpe.typeSymbol.asClass.isSealed) {
      c.abort(c.enclosingPosition, "Routes may only use a sealed trait")
    }
    val clsSymbol = linkTpe.typeSymbol.asClass
    val linkToUrl = generateLinksToUrl(c)("",clsSymbol)
    val urlToLink = generateUrlToLinks[Link](c)(urlRoot(c)("",clsSymbol.fullName),clsSymbol)
    val volatileLinks = generateVolatileLinks(c)(clsSymbol)

    c.Expr[RouteTable[Link]](q"""
      new RouteTable[$linkTpe] {
        import scala.concurrent.{Future,ExecutionContext}

        private val unUrl: Map[String, (String,ExecutionContext) => Future[$linkTpe]] = Map(..$urlToLink)

        def urlFor(link: $linkTpe): String = link match { case ..$linkToUrl }

        def isVolatileLink(link: $linkTpe): Boolean = link match {
          case ..$volatileLinks
          case _ => false
        }

        def linkGiven(url: String, onError: $linkTpe)(implicit ec: ExecutionContext): Future[$linkTpe] = {
          unUrl
            .toList
            .sortBy(_._1)(implicitly[Ordering[String]].reverse)
            .find(part => url.startsWith(part._1)).map
          { case (matched,builder) =>
            val remaining = url.drop(matched.size)
            builder(remaining,ec).recover { case err  => onError }
          }.getOrElse(Future(onError))
        }
      }
    """)
  }

  def generateRouter[Link: c.WeakTypeTag](c: blackbox.Context)(default: c.Expr[Link])(R: c.Expr[Reader[Link]], w: c.Expr[Writer[Link]]): c.Expr[Router[Link]] = {
    import c.universe._
    val linkTpe = weakTypeTag[Link].tpe
    val table = generateRouterTable(c)
    c.Expr[Router[Link]](q"""new Router[$linkTpe]($default,$table)""")
  }

  def generateRouterWithPrefix[Link: c.WeakTypeTag](c: blackbox.Context)(default: c.Expr[Link], urlPrefix: c.Expr[String])(R: c.Expr[Reader[Link]], w: c.Expr[Writer[Link]]): c.Expr[Router[Link]] = {
    import c.universe._
    val linkTpe = weakTypeTag[Link].tpe
    val table = generateRouterTable(c)
    c.Expr[Router[Link]](q"""new Router[$linkTpe]($default,$table,Some($urlPrefix))""")
  }
}