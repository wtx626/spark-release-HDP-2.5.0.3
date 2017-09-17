/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.catalyst.rules

import scala.collection.JavaConverters._

import com.google.common.util.concurrent.AtomicLongMap

import org.apache.spark.Logging
import org.apache.spark.sql.catalyst.trees.TreeNode
import org.apache.spark.sql.catalyst.util.sideBySide

object RuleExecutor {
  protected val timeMap = AtomicLongMap.create[String]()

  /** Resets statistics about time spent running specific rules */
  def resetTime(): Unit = timeMap.clear()

  /** Dump statistics about time spent running specific rules. */
  def dumpTimeSpent(): String = {
    val map = timeMap.asMap().asScala
    val maxSize = map.keys.map(_.toString.length).max
    map.toSeq.sortBy(_._2).reverseMap { case (k, v) =>
      s"${k.padTo(maxSize, " ").mkString} $v"
    }.mkString("\n")
  }
}

abstract class RuleExecutor[TreeType <: TreeNode[_]] extends Logging {

  /**
   * An execution strategy for rules that indicates the maximum number of executions. If the
   * execution reaches fix point (i.e. converge) before maxIterations, it will stop.
   */
  abstract class Strategy { def maxIterations: Int }

  /** A strategy that only runs once. */
  case object Once extends Strategy { val maxIterations = 1 }

  /** A strategy that runs until fix point or maxIterations times, whichever comes first. */
  case class FixedPoint(maxIterations: Int) extends Strategy

  /** A batch of rules. */
  protected case class Batch(name: String, strategy: Strategy, rules: Rule[TreeType]*)

  /** Defines a sequence of rule batches, to be overridden by the implementation. */
  protected val batches: Seq[Batch]


  /**
   * Executes the batches of rules defined by the subclass. The batches are executed serially
   * using the defined execution strategy. Within each batch, rules are also executed serially.
   */
  //执行这个类的子类（Analyzer）定义的batches（也就Analyzer.scala中提到的那些batches），batches中的batch会一个接一个的被调用
  //使用batch中定义的策略（Once，FixedPoint），rules中的rule也会一个接一个的被调用
  def execute(plan: TreeType): TreeType = {
    var curPlan = plan

    batches.foreach { batch =>
      val batchStartPlan = curPlan
      var iteration = 1
      var lastPlan = curPlan
      var continue = true

      // Run until fix point (or the max number of iterations as specified in the strategy.
      while (continue) {
        //调用rules中每一个rule 作用于计划
        //flodLeft是柯里化的一种实现
        // 这个result就是curPlan，而这句代码的最终实现就是依次是用rules中的rule和curPlan来当作传入参数。
        curPlan = batch.rules.foldLeft(curPlan) {
          case (plan, rule) =>
            val startTime = System.nanoTime()
            //调用rule递归对整个tree进行处理
            //这里很显然他是调用了这个rule的apply方法
            val result = rule(plan)
            val runTime = System.nanoTime() - startTime
            RuleExecutor.timeMap.addAndGet(rule.ruleName, runTime)

            if (!result.fastEquals(plan)) {
              //如果处理后的plan有变化，就打印相关信息
              logTrace(
                s"""
                  |=== Applying Rule ${rule.ruleName} ===
                  |${sideBySide(plan.treeString, result.treeString).mkString("\n")}
                """.stripMargin)
            }

            result
        }
        //batch迭代次数+1
        iteration += 1
        //如果迭代次数大于最大值，就停止
        if (iteration > batch.strategy.maxIterations) {
          // Only log if this is a rule that is supposed to run more than once.
          if (iteration != 2) {
            logInfo(s"Max iterations (${iteration - 1}) reached for batch ${batch.name}")
          }
          continue = false
        }

        //如果处理的plan没变化，也停止
        if (curPlan.fastEquals(lastPlan)) {
          logTrace(
            s"Fixed point reached for batch ${batch.name} after ${iteration - 1} iterations.")
          continue = false
        }
        lastPlan = curPlan
      }

      //如果处理的plan有变化，打印相关信息
      if (!batchStartPlan.fastEquals(curPlan)) {
        logDebug(
          s"""
          |=== Result of Batch ${batch.name} ===
          |${sideBySide(plan.treeString, curPlan.treeString).mkString("\n")}
        """.stripMargin)
      } else {
        logTrace(s"Batch ${batch.name} has no effect.")
      }
    }

    curPlan
  }
  //这里的分析阶段完成了sql语句和数据库相关信息的绑定
}
