/*
 * Copyright 2021-2022 John A. De Goes and the ZIO Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package zio.flow.remote.numeric

import zio.schema.{DeriveSchema, Schema}

sealed trait FractionalPredicateOperator

object FractionalPredicateOperator {
  case object IsNaN         extends FractionalPredicateOperator
  case object IsInfinity    extends FractionalPredicateOperator
  case object IsFinite      extends FractionalPredicateOperator
  case object IsPosInfinity extends FractionalPredicateOperator
  case object IsNegInifinty extends FractionalPredicateOperator

  implicit val schema: Schema[FractionalPredicateOperator] = DeriveSchema.gen
}
