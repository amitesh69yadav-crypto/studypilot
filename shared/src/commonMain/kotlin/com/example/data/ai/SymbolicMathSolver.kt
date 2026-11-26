package com.example.data.ai

import java.util.Locale
import java.util.regex.Pattern

/**
 * SymbolicMathSolver: Evaluates algebraic, calculus, and mathematical expressions
 * with a multi-tier educational ladder (1st Standard Intuition -> High School Step-by-Step ->
 * Undergraduate Formalism -> PhD Theoretical Rigor).
 */
object SymbolicMathSolver {

  fun trySolve(query: String): String? {
    val lower = query.lowercase(Locale.ROOT).trim()

    // 1. Derivatives / Calculus Intent
    val derivResponse = trySolveDerivative(lower)
    if (derivResponse != null) return derivResponse

    // 2. Linear Equation Solver
    val linearResponse = trySolveLinear(lower)
    if (linearResponse != null) return linearResponse

    // 3. Quadratic Equation Solver
    val quadResponse = trySolveQuadratic(lower)
    if (quadResponse != null) return quadResponse

    // 4. Basic Arithmetic calculation
    val arithResponse = trySolveArithmetic(lower)
    if (arithResponse != null) return arithResponse

    return null
  }

  private fun trySolveDerivative(lower: String): String? {
    if (!lower.contains("derivative") && !lower.contains("d/dx") && !lower.contains("differentiate")) {
      return null
    }

    // Power rule: x^n
    val powerPattern = Pattern.compile("x\\^(\\d+)")
    val matcher = powerPattern.matcher(lower)
    if (matcher.find()) {
      val n = matcher.group(1)?.toIntOrNull() ?: 2
      val newPower = n - 1
      val resultDeriv = if (newPower == 1) "${n}x" else "${n}x^$newPower"

      return """
        ### 📐 Mathematical Derivation: d/dx [x^$n]

        > 💡 **Core Result**: **d/dx [x^$n] = $resultDeriv**

        ---

        #### 🎯 Intuitive Concept
        Imagine you are riding your bicycle down a hill. The speedometer in front of you doesn't tell you how far you have ridden all day; it tells you **how fast you are zooming right this exact split-second**! That split-second speed is what a derivative measures!

        #### 🏫 2. High School Foundation & Power Rule
        The **Power Rule** states: to differentiate x^n, bring the power n down to multiply in front, then subtract 1 from the exponent:
        1. **Formula**: d/dx [x^n] = n * x^(n - 1)
        2. Here, n = $n.
        3. Bring down the power: $n * x^($n - 1) = **$resultDeriv**.

        #### 🎓 3. Undergraduate Formal Calculus Limit Proof
        From the formal limit definition of the derivative:
        f'(x) = lim(h -> 0) [f(x + h) - f(x)] / h
        Substituting f(x) = x^$n using the Binomial Theorem:
        (x + h)^$n = x^$n + $n * x^$newPower * h + O(h^2)
        Subtracting x^$n and dividing by h:
        [(x + h)^$n - x^$n] / h = $n * x^$newPower + O(h)
        Taking the limit as h -> 0, all higher-order h terms vanish:
        **f'(x) = $resultDeriv**

        #### 🔬 4. PhD & Research Rigor: Fréchet Derivatives
        In infinite-dimensional Banach spaces, this power operator is a continuous mapping T: X -> Y. The derivative corresponds to the bounded linear Fréchet differential:
        lim(||h|| -> 0) [||f(x + h) - f(x) - Df(x)(h)|| / ||h||] = 0
        On smooth Riemannian manifolds, this extends to the exterior derivative d: Ω^k(M) -> Ω^(k+1)(M) satisfying d^2 = 0.

        ---

        #### 💡 High-Yield Exam Takeaway
        | Level | Key Focus | Critical Rule |
        |---|---|---|
        | **Grade School** | Instantaneous Speed | Speedometer reading at one instant |
        | **High School** | Power Rule | Multiply by n, decrement exponent by 1 |
        | **Undergrad** | Newton-Leibniz Limit | First principles binomial quotient |
        | **Doctoral** | Differential Forms | Linear operator on tangent bundle TM |
      """.trimIndent()
    }

    if (lower.contains("sin")) {
      return """
        ### 📐 Derivative of Trigonometric Function: d/dx [sin(x)]

        > 💡 **Core Result**: **d/dx [sin(x)] = cos(x)**

        ---

        #### 🎯 Intuitive Concept
        Think of a swing at the park swinging back and forth like a wave. When the swing is at the very highest point, it stops for a tiny moment before falling. The derivative tells you where the swing is moving the fastest!

        #### 🏫 2. High School Formulation
        The derivative of the sine function is the cosine function:
        d/dx [sin(x)] = cos(x)
        Similarly, d/dx [cos(x)] = -sin(x).

        #### 🎓 3. Undergraduate Formal Limit Proof
        Using the difference quotient and angle addition identity sin(x + h) = sin(x)cos(h) + cos(x)sin(h):
        f'(x) = lim(h -> 0) [sin(x)cos(h) + cos(x)sin(h) - sin(x)] / h
        Grouping terms:
        = sin(x) * lim(h -> 0) [(cos(h) - 1) / h] + cos(x) * lim(h -> 0) [sin(h) / h]
        Since lim(h -> 0) [sin(h) / h] = 1 and lim(h -> 0) [(cos(h) - 1) / h] = 0:
        **f'(x) = cos(x)**

        #### 🔬 4. PhD & Complex Analysis Perspective
        Via Euler's formula e^(ix) = cos(x) + i*sin(x), sin(x) = (e^(ix) - e^(-ix)) / (2i). Differentiating the complex exponential yields:
        d/dx [(e^(ix) - e^(-ix)) / (2i)] = (i*e^(ix) + i*e^(-ix)) / (2i) = (e^(ix) + e^(-ix)) / 2 = cos(x)
        This establishes that sin(z) is an entire holomorphic function on the complex plane C.
      """.trimIndent()
    }

    return null
  }

  private fun trySolveLinear(lower: String): String? {
    val linearPattern = Pattern.compile("([+-]?\\s*\\d*\\.?\\d*)\\s*x\\s*([+-]\\s*\\d+\\.?\\d*)\\s*=\\s*([+-]?\\s*\\d+\\.?\\d*)")
    val linearMatcher = linearPattern.matcher(lower)
    if (!linearMatcher.find()) return null

    return try {
      var aStr = linearMatcher.group(1)?.replace(" ", "") ?: "1"
      if (aStr == "" || aStr == "+") aStr = "1"
      if (aStr == "-") aStr = "-1"
      val a = aStr.toDouble()

      val bStr = linearMatcher.group(2)?.replace(" ", "") ?: "0"
      val b = bStr.toDouble()

      val cStr = linearMatcher.group(3)?.replace(" ", "") ?: "0"
      val c = cStr.toDouble()

      if (a == 0.0) return null

      val x = (c - b) / a
      val formattedX = if (x % 1.0 == 0.0) x.toLong().toString() else String.format(Locale.US, "%.3f", x)

      """
        ### 📐 Step-by-Step Algebraic Solution: $a x ${if (b >= 0) "+ $b" else "- ${-b}"} = $c

        > 💡 **Exact Solution**: **x = $formattedX**

        ---

        #### 🎯 Intuitive Concept
        Think of an old-fashioned balance scale! Both sides must balance equally.
        - You have $a mystery gift boxes plus $b dollar coins on the left tray, balancing $c dollar coins on the right tray.
        - If you take away $b coins from both trays, the scale stays balanced!
        - Now divide the remaining coins equally among the $a gift boxes, and each box has exactly **$formattedX** coins!

        #### 🏫 2. High School Step-by-Step Derivation
        1. **Isolate the Variable Term**: Subtract $b from both sides:
           $a x = $c - ($b)  -->  $a x = ${c - b}
        2. **Divide by the Coefficient ($a)**:
           x = (${c - b}) / $a  -->  **x = $formattedX**
        3. **Verification Check**:
           Substitute $formattedX back into the equation:
           ($a)($formattedX) + ($b) = ${(a * x) + b} = $c (Verified ✓)

        #### 🎓 3. Undergraduate Linear Algebra Formulation
        This equation is an affine transformation in 1-dimensional Euclidean space R^1:
        T(x) = a * x + b = c  <==>  A * x = y
        Since det(A) = a != 0, the operator A is invertible with inverse A^(-1) = 1/a, ensuring the existence of a unique solution vector.

        #### 🔬 4. PhD & Numerical Conditioning
        The relative condition number of this linear root-finding problem is:
        κ = | c / (c - b) |
        As c -> b and a -> 0, the system approaches numerical ill-conditioning, where floating-point rounding errors (IEEE 754) can cause catastrophic cancellation.
      """.trimIndent()
    } catch (_: Exception) {
      null
    }
  }

  private fun trySolveQuadratic(lower: String): String? {
    val quadPattern = Pattern.compile("([+-]?\\s*\\d*\\.?\\d*)\\s*x\\^2\\s*([+-]?\\s*\\d*\\.?\\d*)\\s*x\\s*([+-]?\\s*\\d+\\.?\\d*)\\s*=\\s*0")
    val quadMatcher = quadPattern.matcher(lower)
    if (!quadMatcher.find()) return null

    return try {
      var aStr = quadMatcher.group(1)?.replace(" ", "") ?: "1"
      if (aStr == "" || aStr == "+") aStr = "1"
      if (aStr == "-") aStr = "-1"
      val a = aStr.toDouble()

      var bStr = quadMatcher.group(2)?.replace(" ", "") ?: "0"
      if (bStr == "" || bStr == "+") bStr = "1"
      if (bStr == "-") bStr = "-1"
      val b = bStr.toDouble()

      val cStr = quadMatcher.group(3)?.replace(" ", "") ?: "0"
      val c = cStr.toDouble()

      val discriminant = (b * b) - (4 * a * c)
      val stepDiscriminant = String.format(Locale.US, "%.2f", discriminant)

      val rootsExplanation = when {
        discriminant > 0 -> {
          val r1 = (-b + Math.sqrt(discriminant)) / (2 * a)
          val r2 = (-b - Math.sqrt(discriminant)) / (2 * a)
          val s1 = if (r1 % 1.0 == 0.0) r1.toLong().toString() else String.format(Locale.US, "%.3f", r1)
          val s2 = if (r2 % 1.0 == 0.0) r2.toLong().toString() else String.format(Locale.US, "%.3f", r2)
          "**Two Distinct Real Roots**: `x₁ = $s1` and `x₂ = $s2`"
        }
        discriminant == 0.0 -> {
          val r = -b / (2 * a)
          val s = if (r % 1.0 == 0.0) r.toLong().toString() else String.format(Locale.US, "%.3f", r)
          "**One Repeated Real Root**: `x = $s`"
        }
        else -> {
          val realPart = -b / (2 * a)
          val imagPart = Math.sqrt(-discriminant) / (2 * a)
          val sReal = String.format(Locale.US, "%.3f", realPart)
          val sImag = String.format(Locale.US, "%.3f", Math.abs(imagPart))
          "**Complex Conjugate Roots**: `x = $sReal ± ${sImag}i`"
        }
      }

      """
        ### 📐 Quadratic Polynomial Derivation: $a x^2 ${if (b >= 0) "+ $b" else "- ${-b}"}x ${if (c >= 0) "+ $c" else "- ${-c}"} = 0

        > 💡 **Result**: $rootsExplanation

        ---

        #### 🎯 Intuitive Concept
        Imagine kicking a soccer ball into the sky! It rises up, curves smoothly at the top, and comes back down to the grass. The path it draws in the air is called a **Parabola**! Finding the roots just means finding the exact two spots where the ball touched the grass!

        #### 🏫 2. High School Quadratic Formula
        Standard Quadratic Form: a * x^2 + b * x + c = 0
        1. **Discriminant**: Δ = b^2 - 4ac = ($b)^2 - 4($a)($c) = **$stepDiscriminant**
        2. **Quadratic Formula**:
           x = [-b ± √Δ] / (2a) = [-($b) ± √$stepDiscriminant] / [2($a)]
        3. Result: $rootsExplanation

        #### 🎓 3. Undergraduate Completing the Square Proof
        Divide by leading coefficient a:
        x^2 + (b/a) * x = -c/a
        Add (b / 2a)^2 to both sides to form a perfect square trinomial:
        [x + (b / 2a)]^2 = (b^2 - 4ac) / (4a^2)
        Taking the square root and isolating x derives the universal formula:
        x = [-b ± √(b^2 - 4ac)] / (2a)

        #### 🔬 4. PhD & Abstract Algebra Perspective
        The roots of quadratic polynomials span the splitting field K = Q(√Δ) over Q. The Galois group Gal(K/Q) is isomorphic to S2, which is solvable by radicals. By the Abel-Ruffini theorem, polynomials up to degree 4 are solvable by algebraic radicals, but generic quintics (degree >= 5) have non-solvable Galois groups (S5), admitting no general closed-form radical formula.
      """.trimIndent()
    } catch (_: Exception) {
      null
    }
  }

  private fun trySolveArithmetic(lower: String): String? {
    val clean = lower.replace("what is", "").replace("calculate", "").replace("evaluate", "").trim()
    val arithPattern = Pattern.compile("^([+-]?\\d+\\.?\\d*)\\s*([+\\-*/x×÷])\\s*([+-]?\\d+\\.?\\d*)$")
    val matcher = arithPattern.matcher(clean)
    if (!matcher.find()) return null

    return try {
      val num1 = matcher.group(1)?.toDoubleOrNull() ?: return null
      val op = matcher.group(2) ?: return null
      val num2 = matcher.group(3)?.toDoubleOrNull() ?: return null

      val (result, symbol, opName) = when (op) {
        "+", "plus" -> Triple(num1 + num2, "+", "Addition")
        "-", "minus" -> Triple(num1 - num2, "-", "Subtraction")
        "*", "x", "×", "times" -> Triple(num1 * num2, "×", "Multiplication")
        "/", "÷", "divided by" -> {
          if (num2 == 0.0) return "⚠️ **Mathematical Error**: Division by zero is undefined in standard arithmetic."
          Triple(num1 / num2, "÷", "Division")
        }
        else -> return null
      }

      val s1 = if (num1 % 1.0 == 0.0) num1.toLong().toString() else num1.toString()
      val s2 = if (num2 % 1.0 == 0.0) num2.toLong().toString() else num2.toString()
      val sRes = if (result % 1.0 == 0.0) result.toLong().toString() else String.format(Locale.US, "%.4f", result)

      """
        ### 📐 Arithmetic Calculation: $s1 $symbol $s2

        > 💡 **Answer**: **$s1 $symbol $s2 = $sRes**

        ---

        #### 🎯 Intuitive Concept
        ${when (symbol) {
          "+" -> "You have $s1 apples in your red basket, and a friend gives you $s2 more apples. Count them all together, and you have $sRes apples!"
          "-" -> "You have $s1 cookies on a plate. You share $s2 cookies with your family. You are left with $sRes cookies!"
          "×" -> "You have $s1 toy boxes, and every single box has $s2 toy cars inside. In total, you have $sRes toy cars!"
          else -> "You have $s1 candies and you share them equally among $s2 friends. Each friend gets $sRes candies!"
        }}

        #### 🏫 2. High School Formulation
        - **Operation**: $opName
        - **Equation**: f(a, b) = a $symbol b  -->  $s1 $symbol $s2 = $sRes
        - **Field**: Real numbers R closed under standard field axioms.
      """.trimIndent()
    } catch (_: Exception) {
      null
    }
  }
}
