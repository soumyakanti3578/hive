/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hive.ql.log;

import org.apache.hadoop.hive.ql.session.SessionState;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


@Aspect
public class PerfLoggerAspect {

  private static final Logger LOG = LoggerFactory.getLogger(PerfLoggerAspect.class.getName());

  @Around("@annotation(annotationObj) && execution(* *(..))")
  public Object logPerf(ProceedingJoinPoint joinPoint, LogPerf annotationObj) throws Throwable {
    String className = annotationObj.caller().isEmpty()
        ? joinPoint.getTarget().getClass().getName()
        : annotationObj.caller();
    String methodName = annotationObj.method().isEmpty()
        ? joinPoint.getSignature().getName()
        : annotationObj.method();
    String additionalInfo = annotationObj.additionalInfo().isEmpty()
        ? null
        : annotationObj.additionalInfo();
    LOG.info("joinPoint: {}, caller: {}, method: {}, addInfo: {}",
        joinPoint, annotationObj.caller(), annotationObj.method(), annotationObj.additionalInfo());

    return doPerfLogging(joinPoint, className, methodName, additionalInfo);
  }

  private Object doPerfLogging(ProceedingJoinPoint joinPoint, String caller, String method, String additionalInfo)
      throws Throwable {
    PerfLogger perfLogger = SessionState.getPerfLogger();

    perfLogger.perfLogBegin(caller, method);
    Object proceed = joinPoint.proceed();
    perfLogger.perfLogEnd(caller, method, additionalInfo);

    return proceed;
  }
}
