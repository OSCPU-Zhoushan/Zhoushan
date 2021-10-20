/**************************************************************************************
* Copyright (c) 2021 Li Shi
*
* Zhoushan is licensed under Mulan PSL v2.
* You can use this software according to the terms and conditions of the Mulan PSL v2.
* You may obtain a copy of Mulan PSL v2 at:
*             http://license.coscl.org.cn/MulanPSL2
*
* THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND, EITHER
* EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT, MERCHANTABILITY OR
* FIT FOR A PARTICULAR PURPOSE.
*
* See the Mulan PSL v2 for more details.
***************************************************************************************/

// ref: https://github.com/OSCPU/ysyxSoC/blob/master/ysyx/module-prefix/AddModulePrefix.scala

package zhoushan

import firrtl._
import firrtl.annotations.{ModuleTarget, NoTargetAnnotation}
import firrtl.ir._
import firrtl.stage.Forms
import firrtl.stage.TransformManager.TransformDependency
import firrtl.transforms.DontTouchAnnotation

case class ModulePrefixAnnotation(prefix: String) extends NoTargetAnnotation

class AddModulePrefix extends Transform with DependencyAPIMigration {

  override def prerequisites: Seq[TransformDependency] = Forms.LowForm
  override def optionalPrerequisites:  Seq[TransformDependency] = Forms.LowFormOptimized
  override def optionalPrerequisiteOf: Seq[TransformDependency] = Forms.LowEmitters
  override def invalidates(a: Transform): Boolean = false

  override protected def execute(state: CircuitState): CircuitState = {
    val c = state.circuit

    val prefix = state.annotations.collectFirst {
      case ModulePrefixAnnotation(p) => p
    }.get

    val blacklist = List(
      "S011HD1P_X32Y2D128"
    )

    val extModules = state.circuit.modules.filter({ m =>
      m match {
        case blackbox: ExtModule => true
        case other => false
      }
    }).map(_.name)

    def rename(old: String): String = if (blacklist.map(_ == old).reduce(_ || _)) old
      else if(extModules.contains(old)) old else prefix + old

    val renameMap = RenameMap()

    def onStmt(s: Statement): Statement = s match {
      case DefInstance(info, name, module, tpe) =>
        DefInstance(info, name, rename(module), tpe)
      case other =>
        other.mapStmt(onStmt)
    }

    def onModule(m: DefModule): DefModule = m match {
      case Module(info, name, ports, body) =>
        val newName = rename(name)
        renameMap.record(
          ModuleTarget(c.main, name), ModuleTarget(c.main, newName)
        )
        Module(info, newName, ports, body).mapStmt(onStmt)
      case extModule: ExtModule => extModule
    }

    val newCircuit = c.mapModule(onModule)
    state.copy(
      circuit = newCircuit.copy(main = rename(c.main)),
      renames = Some(renameMap)
    )
  }
}
