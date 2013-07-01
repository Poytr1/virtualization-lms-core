package scala.virtualization.lms
package internal

import java.io.{File, FileWriter, PrintWriter}

import scala.reflect.SourceContext

trait ScalaCodegen extends GenericCodegen with Config {
  val IR: Expressions
  import IR._

  override def kernelFileExt = "scala"

  override def toString = "scala"

  var transformers: List[AbstractTransformer] = List[AbstractTransformer]()

  override def exceptionHandler(e: Exception, outFile:File, kstream:PrintWriter): Unit = {
      e.printStackTrace()
      kstream.close()
      outFile.delete
  }

  def emitSource[A : Manifest](args: List[Sym[_]], body: Block[A], className: String, out: PrintWriter) = {

    val sA = remap(manifest[A])

    val staticData = getFreeDataBlock(body)

    withStream(out) {
      stream.println("/*****************************************\n"+
                     "  Emitting Generated Code                  \n"+
                     "*******************************************/")
      emitFileHeader()

      var transformedBody = body
      transformers foreach { trans =>
        transformedBody = trans.apply[A](body.asInstanceOf[trans.IR.Block[A]]).asInstanceOf[this.Block[A]]
      }

      // TODO: separate concerns, should not hard code "pxX" name scheme for static data here
      stream.println("class "+className+(if (staticData.isEmpty) "" else "("+staticData.map(p=>"p"+quote(p._1, true)+":"+p._1.tp).mkString(",")+")")+" extends (("+args.map( a => remap(a.tp)).mkString(", ")+")=>("+sA+")) {")
      stream.println("def apply("+args.map(a => quote(a, true) + ":" + remap(a.tp)).mkString(", ")+"): "+sA+" = {")
      emitBlock(transformedBody)
      stream.println(quote(getBlockResult(transformedBody)))
    
      stream.println("}")
    
      stream.println("}")
      stream.println("/*****************************************\n"+
                     "  End of Generated Code                  \n"+
                     "*******************************************/")
    }
    
    staticData
  }

  override def emitKernelHeader(syms: List[Sym[Any]], vals: List[Sym[Any]], vars: List[Sym[Any]], resultType: String, resultIsVar: Boolean, external: Boolean): Unit = {
    val kernelName = syms.map(quote).mkString("")
    
    stream.println("package generated." + this.toString)
    stream.println("object kernel_" + kernelName + " {")
    stream.print("def apply(")
    stream.print(vals.map(p => quote(p, true) + ":" + remap(p.tp)).mkString(","))

    // variable name mangling
    if (vals.length > 0 && vars.length > 0){
      stream.print(", ")
    }
    // TODO: remap Ref instead of explicitly adding generated.scala
    if (vars.length > 0){
      stream.print(vars.map(v => quote(v, true) + ":" + "generated.scala.Ref[" + remap(v.tp) +"]").mkString(","))
    }
    if (resultIsVar){
      stream.print("): " + "generated.scala.Ref[" + resultType + "] = {")
    }
    else {
      stream.print("): " + resultType + " = {")
    }

    stream.println("")
  }

  override def emitKernelFooter(syms: List[Sym[Any]], vals: List[Sym[Any]], vars: List[Sym[Any]], resultType: String, resultIsVar: Boolean, external: Boolean): Unit = {
    val kernelName = syms.map(quote).mkString("")
    stream.println(kernelName)
    stream.println("}}")
  }

  def relativePath(fileName: String): String = {
    val i = fileName.lastIndexOf('/')
    fileName.substring(i + 1)
  }

  def emitValDef(sym: Sym[Any], rhs: String): Unit = {
    val extra = if ((sourceinfo < 2) || sym.pos.isEmpty) "" else {
      val context = sym.pos(0)
      "      // " + relativePath(context.fileName) + ":" + context.line
    }
    sym match {
      case s@Sym(n) => isVoidType(s.tp) match {
        case true => stream.println(/*"val " + quote(sym) + " = " +*/ "" + rhs + extra)
        case false => stream.println("val " + quote(sym) + " = " + rhs + extra)
      }
      case _ => stream.println("val " + quote(sym) + " = " + rhs + extra)
    }
  }
  
  def emitVarDef(sym: Sym[Variable[Any]], rhs: String): Unit = {
    stream.println("var " + quote(sym, true) + ": " + remap(sym.tp) + " = " + rhs)
  }
  
  def emitAssignment(sym: Sym[Any], lhs: String, rhs: String): Unit = {
    if(isVoidType(sym.tp)) {
      stream.println(lhs + " = " + rhs)
    } else {
      emitValDef(sym, lhs + " = " + rhs)
    }
  }
}

trait ScalaNestedCodegen extends GenericNestedCodegen with ScalaCodegen {
  val IR: Expressions with Effects
  import IR._
  
  // emit forward decls for recursive vals
  override def traverseStmsInBlock[A](stms: List[Stm]): Unit = {
    recursive foreach emitForwardDef
    super.traverseStmsInBlock(stms)
  }
  
  def emitForwardDef(sym: Sym[Any]): Unit = {
    if(!isVoidType(sym.tp)) { stream.println("var " + quote(sym, true) + /*": " + remap(sym.tp) +*/ " = null.asInstanceOf[" + remap(sym.tp) + "]") }
  }
  
  // special case for recursive vals
  override def emitValDef(sym: Sym[Any], rhs: String): Unit = {
    if (recursive contains sym)
      stream.println(quote(sym) + " = " + rhs) // we have a forward declaration above.
    else
      super.emitValDef(sym,rhs)
  }
  
}


trait ScalaFatCodegen extends GenericFatCodegen with ScalaCodegen {
  val IR: Expressions with Effects with FatExpressions
  import IR._
  
  def emitKernelExtra(syms: List[Sym[Any]]): Unit = {
    val kernelName = syms.map(quote).mkString("")
    stream.println("final class activation_" + kernelName + " {")
    for (s <- syms) {
      stream.println("var " + quote(s) + ": " + remap(s.tp) + " = _")
    }
    stream.println("}")
  }
  
  override def emitFatNodeKernelExtra(syms: List[Sym[Any]], rhs: FatDef): Unit = emitKernelExtra(syms)
  override def emitNodeKernelExtra(syms: List[Sym[Any]], rhs: Def[Any]): Unit = emitKernelExtra(syms)


}
