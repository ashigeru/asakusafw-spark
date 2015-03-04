package com.asakusafw.spark.compiler.operator
package core

import org.objectweb.asm.Type

import com.asakusafw.lang.compiler.model.graph.CoreOperator
import com.asakusafw.lang.compiler.model.graph.CoreOperator.CoreOperatorKind
import com.asakusafw.spark.compiler.spi.CoreOperatorCompiler

class ExtendOperatorCompiler extends CoreOperatorCompiler {

  override def of: CoreOperatorKind = CoreOperatorKind.EXTEND

  override def compile(operator: CoreOperator)(implicit context: Context): (Type, Array[Byte]) = {
    ???
  }
}