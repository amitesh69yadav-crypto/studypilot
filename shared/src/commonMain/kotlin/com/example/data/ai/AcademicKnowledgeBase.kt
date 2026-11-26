package com.example.data.ai

import java.util.Locale

/**
 * AcademicKnowledgeBase: Pre-compiled, non-vague academic knowledge graph covering
 * Computer Science, Mathematics, Physics, Chemistry, Biology & Medicine, Economics,
 * Law, Philosophy, and Curiosity questions across multi-tier academic depths.
 */
object AcademicKnowledgeBase {

  private val articles = mutableMapOf<String, MultiLevelAcademicArticle>()

  private val NON_ALNUM_REGEX = Regex("[^a-z0-9\\s]")
  private val WS_REGEX = Regex("\\s+")
  private val ignoredWords = setOf(
    "what", "is", "are", "the", "a", "an", "of", "for", "in", "on", "to", "how", "does", "do",
    "why", "explain", "define", "tell", "me", "about", "algorithm", "algorithms", "method",
    "concept", "theory", "code", "example", "examples", "work", "works"
  )

  private data class PrecompiledArticleEntry(
    val key: String,
    val normKey: String,
    val boundaryRegex: Regex,
    val stemmedKeyWords: List<String>,
    val article: MultiLevelAcademicArticle
  )

  private val sortedEntries: List<PrecompiledArticleEntry>

  init {
    loadArticles()
    sortedEntries = articles.entries
      .sortedByDescending { it.key.length }
      .map { (key, article) ->
        val normKey = key.replace("-", " ")
        val keyWords = normKey
          .split(" ")
          .filter { it.length >= 2 && it !in ignoredWords }
          .map { singularVariants(it).last() }
        PrecompiledArticleEntry(
          key = key,
          normKey = normKey,
          boundaryRegex = Regex("\\b" + Regex.escape(normKey) + "\\b"),
          stemmedKeyWords = keyWords,
          article = article
        )
      }
  }

  private fun singularVariants(word: String): List<String> {
    val list = ArrayList<String>(3)
    list.add(word)
    if (word.length > 4 && word.endsWith("es")) list.add(word.dropLast(2))
    if (word.length > 3 && word.endsWith("s") && !word.endsWith("ss")) list.add(word.dropLast(1))
    return list
  }

  fun search(query: String): MultiLevelAcademicArticle? {
    val clean = query.lowercase(Locale.ROOT)
      .replace("-", " ")
      .replace(NON_ALNUM_REGEX, " ")
      .replace(WS_REGEX, " ")
      .trim()

    // 1. Direct key match
    articles[clean]?.let { return it }

    val queryWords = clean.split(" ").filter { it.isNotBlank() }
    val coreQueryWords = queryWords
      .filter { it.length >= 2 && it !in ignoredWords }
      .flatMap { singularVariants(it) }
      .distinct()

    // 2. Match exact distinctive single word (e.g. "dijkstra", "bellman", "bfs", "acid", "tcp", "jvm", "object")
    for (word in coreQueryWords) {
      articles[word]?.let { return it }
    }

    // 3. Exact phrase containment with word boundaries (longer keys preferred first)
    for (entry in sortedEntries) {
      if (clean == entry.normKey || entry.boundaryRegex.containsMatchIn(clean)) {
        return entry.article
      }
    }

    // 4. Scored word-overlap across keys (never substring false positives like "contract" matching "act")
    if (coreQueryWords.isEmpty()) return null
    val coreQuerySet = coreQueryWords.toHashSet()
    var bestMatch: MultiLevelAcademicArticle? = null
    var bestKeyScore = 0
    for (entry in sortedEntries) {
      val keyWords = entry.stemmedKeyWords
      if (keyWords.isEmpty()) continue
      val matchedCount = keyWords.count { it in coreQuerySet }
      if (matchedCount == keyWords.size || (matchedCount >= 2 && matchedCount > bestKeyScore)) {
        if (matchedCount > bestKeyScore) {
          bestKeyScore = matchedCount
          bestMatch = entry.article
        }
      }
    }
    return bestMatch
  }

  fun formatArticle(
    article: MultiLevelAcademicArticle,
    targetLevel: String? = null
  ): String {
    val q = targetLevel?.lowercase(Locale.ROOT).orEmpty()
    val wantsTrap = q.contains("trap") || q.contains("mistake") || q.contains("fail") ||
      q.contains("error") || q.contains("pitfall") || q.contains("wrong") || q.contains("avoid") || q.contains("misconception")
    val wantsCode = q.contains("code") || q.contains("syntax") || q.contains("implement") ||
      q.contains("snippet") || q.contains("program") || q.contains("formula") || q.contains("equation")
    val wantsSimple = q.contains("simple") || q.contains("beginner") || q.contains("eli5") ||
      q.contains("easy") || q.contains("analogy") || q.contains("intuitive")
    val wantsAdvanced = q.contains("phd") || q.contains("research") || q.contains("advanced") ||
      q.contains("deep") || q.contains("proof") || q.contains("theory") || q.contains("internal")
    val wantsHowOrWhy = q.contains("how") || q.contains("why") || q.contains("work") ||
      q.contains("mechanism") || q.contains("memory") || q.contains("heap") || q.contains("stack") || q.contains("difference")

    return buildString {
      append("### 🧠 Topic Breakdown: ${article.title}\n\n")

      when {
        wantsTrap -> {
          append("> ⚠️ **Direct Answer (Common Trap / Misconception):** ${article.examTrap}\n\n")
          append("**Core Summary:** ${article.thesis}\n")
        }
        wantsCode -> {
          article.formulaOrCode?.let { (lang, code) ->
            append("```$lang\n")
            append(code.trim())
            append("\n```\n\n")
          }
          append("**Topic Explanation:** ${article.highSchool}\n\n")
          append("**Concrete Example:** ${article.elementary}\n")
        }
        wantsSimple -> {
          append("> 💡 **Direct Answer (Simple Explanation):** ${article.elementary}\n\n")
          append("**Core Summary:** ${article.thesis}\n")
        }
        wantsAdvanced -> {
          append("> 🔬 **Direct Answer (Advanced Depth):** ${article.phdResearch}\n\n")
          append("**System Mechanics:** ${article.undergraduate}\n")
          article.formulaOrCode?.let { (lang, code) ->
            append("\n```$lang\n${code.trim()}\n```")
          }
        }
        wantsHowOrWhy -> {
          append("> 📌 **Direct Answer (Core Summary):** ${article.thesis}\n\n")
          append("#### ⚙️ Topic Explanation: How It Works\n")
          append("- **Core Principle:** ${article.highSchool}\n")
          append("- **Under the Hood:** ${article.undergraduate}\n\n")
          append("#### 💡 Concrete Example\n")
          append("- ${article.elementary}\n")
        }
        else -> {
          append("> 📌 **Direct Answer (Core Summary):** ${article.thesis}\n\n")
          append("#### 📖 Topic Explanation\n")
          append("- **Core Principle:** ${article.highSchool}\n")
          append("- **System Mechanics:** ${article.undergraduate}\n\n")
          article.formulaOrCode?.let { (lang, code) ->
            append("```$lang\n")
            append(code.trim())
            append("\n```\n\n")
          }
          append("#### 💡 Concrete Example\n")
          append("- **Practical Scenario:** ${article.elementary}\n\n")
          if (!article.examTrap.isNullOrBlank()) {
            append("- **⚠️ Watch Out:** ${article.examTrap}")
          }
        }
      }
    }.trim()
  }

  private fun loadArticles() {
    // -------------------------------------------------------------
    // COMPUTER SCIENCE & SOFTWARE ENGINEERING
    // -------------------------------------------------------------
    articles["java object"] = MultiLevelAcademicArticle(
      title = "Java Object: Memory Layout, Identity & Lifecycle",
      domain = "Computer Science (OOP & Runtimes)",
      thesis = "An Object in Java is a dynamically instantiated runtime entity on the JVM Heap containing encapsulated instance variables and behavior defined by its blueprint Class.",
      elementary = "Imagine a toy factory paper blueprint for a Lego sports car. The blueprint is drawn on flat paper, but when you snap the real plastic bricks together on your rug, that real toy car you can hold in your hands and drive across the floor is the \"Object\"!",
      highSchool = "A Class is the template, and an Object is the concrete instance. Every object has attributes (like color, speed) and methods (like drive(), brake()). In Java, objects are instantiated using the `new` keyword, which triggers constructor initialization.",
      undergraduate = "Java objects reside on the JVM Heap with a standard 8-to-16 byte object header (Mark Word for hash code, GC age, lock state; Klass Word pointing to class metadata in Metaspace). References live in local stack frames. Virtual method calls are resolved dynamically via the virtual method table (vtable).",
      phdResearch = "Escape Analysis in modern JIT compilers (HotSpot C2 / GraalVM) analyzes whether an object reference escapes the allocating thread/method scope. If non-escaping, scalar replacement decomposes the object into primitive CPU registers, eliminating heap allocation and GC pressure entirely. Object identity interacts with biased locking and compressed OOPs (-XX:+UseCompressedOops) to optimize memory footprint on 64-bit architectures.",
      formulaOrCode = Pair("java", """
        public class Car {
            private String model;
            private int speed;

            public Car(String model, int speed) {
                this.model = model;
                this.speed = speed;
            }

            public void accelerate(int delta) {
                this.speed += delta;
            }
        }
        // In JVM Heap:
        Car myCar = new Car("Falcon", 120);
      """.trimIndent()),
      summaryTable = listOf(
        Pair("Elementary", "Real plastic Lego car built from paper blueprints"),
        Pair("High School", "Instance with attributes & methods created via new"),
        Pair("Undergraduate", "Heap allocation, Mark/Klass object header, vtable dispatch"),
        Pair("PhD Level", "Escape analysis, scalar replacement, biased locking")
      ),
      examTrap = "Confusing `==` (memory reference identity) with `.equals()` (semantic value equivalence)."
    )
    articles["object"] = articles["java object"]!!

    articles["class"] = MultiLevelAcademicArticle(
      title = "Class: Blueprint, Type Abstraction & Modular Encapsulation",
      domain = "Computer Science (OOP)",
      thesis = "A Class is an extensible program-code-template defining the initial state (fields) and implementations of behavior (methods) common to all created instances.",
      elementary = "A cookie cutter! You press the metal star-shaped cookie cutter into dough to stamp out 10 sweet cookies. The cutter is the Class; each baked cookie is an Object!",
      highSchool = "A class groups data and functions into a single reusable unit. Access modifiers (public, private, protected) ensure data encapsulation, preventing external code from corrupting internal variables.",
      undergraduate = "Classes are loaded into memory dynamically by JVM ClassLoaders (Bootstrap, Platform, Application). Bytecode metadata is stored in Metaspace. Constructors initialize instance state after the memory allocator reserves heap space.",
      phdResearch = "Nominal vs Structural typing (Java vs TypeScript/Go). Dynamic class redefining via JVM Tool Interface (JVMTI) and HotSwap limitations on structural alterations. Type erasure in generics ensures backward compatibility but prevents runtime reification.",
      formulaOrCode = Pair("kotlin", """
        class BankAccount(private val accountNumber: String, var balance: Double) {
            fun deposit(amount: Double) {
                require(amount > 0) { "Deposit must be positive" }
                balance += amount
            }
        }
      """.trimIndent()),
      summaryTable = listOf(
        Pair("Elementary", "The cookie cutter that stamps out cookies"),
        Pair("High School", "Encapsulated blueprint with fields and methods"),
        Pair("Undergraduate", "Classloader lifecycle, Metaspace layout, bytecode"),
        Pair("PhD Level", "Nominal typing, type erasure, JVMTI hotswapping")
      ),
      examTrap = "Exposing private fields without getters/setters, violating object encapsulation."
    )

    articles["inheritance"] = MultiLevelAcademicArticle(
      title = "Inheritance: Taxonomic Hierarchy & Subtyping",
      domain = "Computer Science (Software Architecture)",
      thesis = "A mechanism where a child class inherits state and behavior from a parent superclass, establishing an 'IS-A' relationship and enabling code reuse.",
      elementary = "Like getting your mom's bright eyes and your dad's curly hair, but you also learn how to ride a bicycle and play soccer all by yourself!",
      highSchool = "A subclass uses `extends` to inherit all non-private fields and methods from the superclass. The subclass can override existing methods with `@Override` to customize its behavior.",
      undergraduate = "Enforces the Liskov Substitution Principle (LSP): subtypes must be substitutable for base types without altering program correctness. Java permits single-class inheritance to avoid the C++ diamond problem, but allows multiple interface implementation.",
      phdResearch = "The Fragile Base Class Problem: modifications to base class semantics can inadvertently break subclass invariants across large codebases. Modern software architecture strictly favors Composition over Inheritance ('HAS-A' over 'IS-A') and prefers sealed algebraic data types.",
      formulaOrCode = Pair("kotlin", """
        open class Vehicle(val maxSpeed: Int)
        class ElectricCar(maxSpeed: Int, val batteryCapacityKWh: Double) : Vehicle(maxSpeed)
      """.trimIndent()),
      summaryTable = listOf(
        Pair("Elementary", "Inheriting parent traits while having your own unique skills"),
        Pair("High School", "Subclasses reusing code and overriding parent methods"),
        Pair("Undergraduate", "Liskov Substitution Principle (LSP) and single inheritance"),
        Pair("PhD Level", "Fragile base class problem; favor composition over inheritance")
      ),
      examTrap = "Creating deeply nested inheritance trees (>3 levels) which lead to high coupling."
    )

    articles["polymorphism"] = MultiLevelAcademicArticle(
      title = "Polymorphism: Dynamic Dispatch & Multiple Forms",
      domain = "Computer Science (Type Systems)",
      thesis = "The provision of a single interface to entities of different types, resolved either statically at compile-time or dynamically at runtime.",
      elementary = "A TV remote with a 'Power' button! Press it at the television, the screen lights up; press it at your stereo, music plays! One single button, different magic actions!",
      highSchool = "Greek for 'many shapes'. Static polymorphism (Method Overloading) happens at compile-time when functions share a name with different parameters. Dynamic polymorphism (Method Overriding) happens at runtime when a child class implements a parent method.",
      undergraduate = "Dynamic polymorphism is executed via the Virtual Method Table (vtable). Each class possesses a table of function pointers. When obj.draw() is called, the CPU looks up the offset in the runtime object's vtable.",
      phdResearch = "Polymorphic Inline Caching (PIC) and monomorphic vs bimorphic vs megamorphic call sites in optimizing JIT compilers. When call sites exceed the monomorphic threshold (>2 concrete classes), devirtualization and inlining fail, incurring branch penalty and cache miss overhead.",
      formulaOrCode = Pair("kotlin", """
        interface Shape { fun area(): Double }
        class Circle(val r: Double) : Shape { override fun area() = Math.PI * r * r }
        class Square(val s: Double) : Shape { override fun area() = s * s }
      """.trimIndent()),
      summaryTable = listOf(
        Pair("Elementary", "One magic button that does the right thing for each toy"),
        Pair("High School", "Overloading (compile-time) vs Overriding (runtime)"),
        Pair("Undergraduate", "Virtual method table (vtable) and dynamic dispatch"),
        Pair("PhD Level", "Polymorphic inline caching and megamorphic call sites")
      ),
      examTrap = "Invoking an overridable method inside a parent constructor before the child fields initialize."
    )

    articles["jvm"] = MultiLevelAcademicArticle(
      title = "Java Virtual Machine (JVM) Architecture & Execution Engine",
      domain = "Computer Science (Runtime Systems)",
      thesis = "An abstract computing specification providing an execution environment for compiled bytecode across heterogeneous hardware architectures.",
      elementary = "A universal magical translator! You write down your game instructions in one secret code, and the JVM translates it so iPhones, Windows laptops, and Android tablets all understand!",
      highSchool = "Enables 'Write Once, Run Anywhere'. Converts source code into `.class` bytecode files, which the JVM executes using an interpreter, a Just-In-Time (JIT) compiler, and automatic Garbage Collection (GC).",
      undergraduate = "Memory architecture divides into: Heap (all objects and arrays), Stack (per-thread stack frames, local primitives, method call frames), Metaspace (class metadata), PC Registers, and Native Stacks. Execution Engine uses tiered compilation (C1 client compiler for quick startup, C2 server compiler for aggressive inlining and loop unrolling).",
      phdResearch = "Low-latency garbage collection (ZGC, Shenandoah) utilizing colored 64-bit pointers and load-reference barriers to achieve sub-millisecond Stop-The-World (STW) pauses on multi-terabyte heaps. The Java Memory Model (JMM, JSR-133) provides a formal happens-before partial order across volatile variables and memory barriers.",
      formulaOrCode = Pair("text", """
        [Java Source .java] -> [javac Compiler] -> [Bytecode .class]
                 |
                 v
        [JVM: ClassLoader -> Metaspace / Heap / Stack -> JIT C1/C2 -> Native CPU Assembly]
      """.trimIndent()),
      summaryTable = listOf(
        Pair("Elementary", "A universal translator that lets games run on every computer"),
        Pair("High School", "Bytecode execution, JIT compilation, and Garbage Collection"),
        Pair("Undergraduate", "Heap, Stack, Metaspace layout and C1/C2 tiered compilation"),
        Pair("PhD Level", "Colored pointers, load barriers, and happens-before JMM semantics")
      ),
      examTrap = "Confusing StackOverflowError (recursive stack frames) with OutOfMemoryError (exhausted heap memory)."
    )

    articles["tcp"] = MultiLevelAcademicArticle(
      title = "Transmission Control Protocol (TCP) Architecture",
      domain = "Computer Science (Computer Networks)",
      thesis = "A connection-oriented, transport-layer protocol providing reliable, ordered, and error-checked delivery of octet streams between applications.",
      elementary = "Like sending a package with a friendly delivery driver who knocks on your door, hands you the package, and requires you to sign a paper saying you safely got it!",
      highSchool = "Connection-oriented protocol used for the web (HTTP), email, and file transfers. Establishes a connection with a Three-Way Handshake (3-Way Handshake: SYN -> SYN-ACK -> ACK) and retransmits any lost packets.",
      undergraduate = "Implements sliding window flow control (preventing sender from overwhelming receiver buffer) and congestion control algorithms (Slow Start, Congestion Avoidance, AIMD, CUBIC, BBR). Packets carry sequence and acknowledgement numbers.",
      phdResearch = "Head-of-Line (HOL) blocking in multi-stream transport: a single packet drop at the IP layer stalls delivery of all subsequent data streams. Modern internet protocols (HTTP/3, QUIC) replace TCP with UDP-encapsulated multiplexed streams with zero-RTT cryptographic connection resumption.",
      formulaOrCode = Pair("text", """
        Client                     Server
          | ------- SYN (seq=x) ------> |
          | <--- SYN-ACK (ack=x+1) ---- |
          | ------- ACK (ack=y+1) ----> | [Connection Established]
      """.trimIndent()),
      summaryTable = listOf(
        Pair("Elementary", "A certified courier that waits for your signed receipt"),
        Pair("High School", "3-way handshake and guaranteed packet retransmission"),
        Pair("Undergraduate", "Sliding window, AIMD, CUBIC congestion control"),
        Pair("PhD Level", "Head-of-line blocking, QUIC/HTTP-3 user-space transport")
      ),
      examTrap = "Assuming TCP is faster than UDP; TCP trade-offs latency for reliability."
    )

    articles["acid"] = MultiLevelAcademicArticle(
      title = "ACID Properties & Database Transaction Theory",
      domain = "Computer Science (Database Systems)",
      thesis = "A set of four properties guaranteeing that database transactions are processed reliably, preserving structural consistency despite concurrent access or hardware failure.",
      elementary = "An ATM bank machine! If you withdraw 10 dollars, the machine spits out cash AND subtracts 10 dollars from your account. It can never do one without the other, even if lightning hits the bank!",
      highSchool = "Atomicity (all operations succeed or all roll back), Consistency (rules and schemas preserved), Isolation (simultaneous transactions don't interfere), Durability (saved data survives crashes).",
      undergraduate = "Implemented via Write-Ahead Logging (WAL) and ARIES recovery for Atomicity and Durability. Isolation levels (Read Uncommitted, Read Committed, Repeatable Read, Serializable) managed through Two-Phase Locking (2PL) or Multi-Version Concurrency Control (MVCC).",
      phdResearch = "CAP Theorem (Brewer) and PACELC trade-offs in distributed datastores: under network partitions, strict ACID consistency (CP) requires sacrificing Availability (AP). Spanner uses TrueTime (GPS receivers + atomic clocks) to enforce external consistency (linearizability) across global clusters without central locks.",
      formulaOrCode = Pair("sql", """
        BEGIN TRANSACTION;
        UPDATE accounts SET balance = balance - 100 WHERE id = 1;
        UPDATE accounts SET balance = balance + 100 WHERE id = 2;
        COMMIT; -- Atomicity guarantees both updates or neither!
      """.trimIndent()),
      summaryTable = listOf(
        Pair("Elementary", "An all-or-nothing magic rule for cash machines"),
        Pair("High School", "Atomicity, Consistency, Isolation, and Durability"),
        Pair("Undergraduate", "Write-Ahead Logging (WAL), MVCC, and isolation levels"),
        Pair("PhD Level", "CAP theorem, PACELC, distributed linearizability & TrueTime")
      ),
      examTrap = "Assuming NoSQL databases support full ACID by default without checking their eventual consistency model."
    )

    articles["deadlock"] = MultiLevelAcademicArticle(
      title = "Operating System Deadlock & Coffman Conditions",
      domain = "Computer Science (Operating Systems & Concurrency)",
      thesis = "A state in which a set of concurrent processes are permanently blocked because each process holds a resource and waits for another resource held by another process.",
      elementary = "Two puppies grabbing opposite ends of the same chew toy! Neither puppy will let go, so both puppies are completely stuck and nobody gets to play!",
      highSchool = "A four-way traffic gridlock where car A blocks car B, car B blocks car C, and car C blocks car A. Nobody can move forward.",
      undergraduate = "Requires all 4 Coffman Conditions simultaneously: 1. Mutual Exclusion, 2. Hold and Wait, 3. No Preemption, 4. Circular Wait. Prevention breaks one condition; avoidance uses Dijkstra's Banker's Algorithm (safe states); detection uses Resource Allocation Graphs with cycle detection.",
      phdResearch = "Wait-free and lock-free concurrent synchronization using atomic Compare-And-Swap (CAS) primitives and Herlihy's consensus hierarchy. Hardware Transactional Memory (HTM) and software transactional memory (STM) dynamically abort and retry conflicting memory transactions without explicit mutexes.",
      formulaOrCode = Pair("text", """
        Thread 1: Locks Res A -> Waits for Res B
        Thread 2: Locks Res B -> Waits for Res A  ==> DEADLOCK!
      """.trimIndent()),
      summaryTable = listOf(
        Pair("Elementary", "Two puppies refusing to let go of the same toy"),
        Pair("High School", "Four-way traffic gridlock where all cars block each other"),
        Pair("Undergraduate", "The 4 Coffman conditions, Banker's algorithm, cycle graphs"),
        Pair("PhD Level", "Lock-free synchronization, CAS primitives, Herlihy consensus")
      ),
      examTrap = "Believing deadlocks only occur in C/C++; high-level database transactions frequently experience deadlock."
    )

    // -------------------------------------------------------------
    // MATHEMATICS & STATISTICS
    // -------------------------------------------------------------
    articles["bayes"] = MultiLevelAcademicArticle(
      title = "Bayes' Theorem & Conditional Probability",
      domain = "Mathematics (Probability & Statistics)",
      thesis = "A mathematical theorem stating how the conditional probability of an event should be updated based on prior knowledge and observed empirical evidence.",
      elementary = "If it rains outside, the sidewalk gets wet! But if the sidewalk is wet, did it definitely rain? Maybe someone was watering flowers with a garden hose! Bayes' rule helps you find the true chance!",
      highSchool = "Formula: P(A|B) = [P(B|A) * P(A)] / P(B). Updates the probability of hypothesis A after observing evidence B. Essential in understanding medical diagnostic test accuracy.",
      undergraduate = "Distinguishes Prior P(H), Likelihood P(E|H), Marginal Evidence P(E) = sum(P(E|H_i) * P(H_i)), and Posterior P(H|E). Demonstrates the Base Rate Fallacy: a test that is 99% accurate can still produce more false positives than true positives if the disease base rate is 1 in 10,000.",
      phdResearch = "Bayesian inference vs Frequentist statistical paradigms. Conjugate priors (e.g. Beta-Binomial, Dirichlet-Multinomial). Intractable high-dimensional posteriors are sampled using Markov Chain Monte Carlo (MCMC: Metropolis-Hastings, Hamiltonian Monte Carlo) or approximated via Variational Inference (optimizing the Evidence Lower Bound - ELBO).",
      formulaOrCode = Pair("text", """
        P(H|E) = [P(E|H) * P(H)] / P(E) = [P(E|H) * P(H)] / [sum_i P(E|H_i) * P(H_i)]
      """.trimIndent()),
      summaryTable = listOf(
        Pair("Elementary", "Wet sidewalks don't always mean it rained"),
        Pair("High School", "P(A|B) updating formula and medical test accuracy"),
        Pair("Undergraduate", "Prior, Likelihood, Posterior, and Base Rate Fallacy"),
        Pair("PhD Level", "MCMC, Hamiltonian Monte Carlo, and Variational Inference")
      ),
      examTrap = "Ignoring the prior base rate P(H), resulting in the Base Rate Fallacy."
    )
    articles["bayes theorem"] = articles["bayes"]!!

    articles["derivative"] = MultiLevelAcademicArticle(
      title = "Derivatives: Instantaneous Rate of Change & Tangent Slopes",
      domain = "Mathematics (Calculus & Analysis)",
      thesis = "The foundational calculus operator measuring the sensitivity of a function output to an infinitesimal variation in its input variable.",
      elementary = "Looking at your bicycle speedometer! It doesn't tell you how far you have ridden all day; it tells you exactly how fast your wheels are spinning right this very split-second!",
      highSchool = "The slope of the tangent line to a curve at a single point. Power Rule: d/dx(x^n) = n * x^(n-1). Product rule, quotient rule, and chain rule (d/dx[f(g(x))] = f'(g(x)) * g'(x)).",
      undergraduate = "Formal limit definition: f'(x) = lim(h -> 0) [f(x+h) - f(x)] / h. Continuity is necessary but not sufficient for differentiability (e.g. Weierstrass continuous everywhere, differentiable nowhere). Higher-order derivatives yield Taylor series expansions.",
      phdResearch = "Generalization to infinite-dimensional spaces via Fréchet and Gâteaux derivatives in Banach spaces. Subgradients and proximal operators for non-smooth convex optimization. Weak derivatives in Sobolev spaces W^{k,p} enabling existence proofs for partial differential equations (PDEs) via the Lax-Milgram theorem.",
      formulaOrCode = Pair("text", """
        f'(x) = lim(h -> 0) [f(x+h) - f(x)] / h
        Power Rule: d/dx [x^n] = n * x^(n-1)
      """.trimIndent()),
      summaryTable = listOf(
        Pair("Elementary", "Speedometer reading at a single frozen moment"),
        Pair("High School", "Tangent slope, power rule, and chain rule"),
        Pair("Undergraduate", "Formal epsilon-delta limits and Taylor polynomial approximations"),
        Pair("PhD Level", "Fréchet derivatives in Banach spaces, Sobolev weak derivatives")
      ),
      examTrap = "Assuming that if a function is continuous, it must be differentiable."
    )
    articles["calculus"] = articles["derivative"]!!

    articles["eigenvalue"] = MultiLevelAcademicArticle(
      title = "Eigenvalues & Eigenvectors: Invariant Transformation Directions",
      domain = "Mathematics (Linear Algebra & Operator Theory)",
      thesis = "Characteristic scalar-vector pairs where a linear transformation operates purely by scaling without rotating the vector.",
      elementary = "Stretching a rubber band! Some lines on the rubber band get longer or shorter, but don't turn or twist at all—those special directions are eigenvectors!",
      highSchool = "An eigenvector v of matrix A satisfies A * v = λ * v, where λ is the scalar eigenvalue. The direction remains unchanged; only the length scales by factor λ.",
      undergraduate = "Solved via the Characteristic Polynomial: det(A - λI) = 0. Algebraic Multiplicity (multiplicity as root) vs Geometric Multiplicity (dimension of eigenspace Null(A - λI)). A matrix is diagonalizable if and only if geometric multiplicity equals algebraic multiplicity for all eigenvalues.",
      phdResearch = "Spectral Theorem for self-adjoint (Hermitian) operators in Hilbert spaces: every normal compact operator admits an orthonormal eigenbasis. Singular Value Decomposition (SVD: A = U * Σ * V^T) generalizes eigenvalues to arbitrary rectangular matrices, forming the mathematical backbone of Principal Component Analysis (PCA) and deep learning weight factorizations.",
      formulaOrCode = Pair("text", """
        A * v = λ * v  <==>  (A - λ*I) * v = 0  ==>  det(A - λ*I) = 0
      """.trimIndent()),
      summaryTable = listOf(
        Pair("Elementary", "Lines on a stretched rubber band that don't twist"),
        Pair("High School", "Vectors whose direction never changes under multiplication"),
        Pair("Undergraduate", "det(A - λI) = 0, eigenspaces, and matrix diagonalization"),
        Pair("PhD Level", "Spectral Theorem on Hilbert spaces, SVD, and PCA")
      ),
      examTrap = "Assuming all square matrices are diagonalizable; defective matrices lack a full eigenbasis."
    )
    articles["eigenvector"] = articles["eigenvalue"]!!

    // -------------------------------------------------------------
    // PHYSICS
    // -------------------------------------------------------------
    articles["newton"] = MultiLevelAcademicArticle(
      title = "Newton's Laws of Motion & Classical Dynamics",
      domain = "Physics (Classical Mechanics)",
      thesis = "Three physical principles formulating the relationship between the motion of physical bodies and the vector forces acting upon them.",
      elementary = "1. A toy ball keeps rolling until grass stops it. 2. Pushing a heavy truck is much harder than pushing a light wagon! 3. When you jump off a skateboard, you fly forward and the board flies backward!",
      highSchool = "1. Law of Inertia (constant velocity unless net external force). 2. Force equals mass times acceleration: F_net = m * a. 3. Action and Reaction: equal in magnitude and opposite in direction on separate bodies.",
      undergraduate = "Momentum formulation: F_net = dp/dt. Conservation of linear and angular momentum. Frame of reference: inertial vs non-inertial frames requiring fictitious inertial forces (Coriolis and centrifugal forces).",
      phdResearch = "Principle of Stationary Action (Hamilton's Principle): reformulates Newtonian mechanics into Lagrangian (L = T - V) and Hamiltonian (H(q, p)) formalisms. Noether's Theorem establishes that physical conservation laws (energy, momentum, angular momentum) are direct mathematical consequences of continuous spacetime symmetries.",
      formulaOrCode = Pair("text", """
        F = dp/dt = m * a   and   F_action = -F_reaction
      """.trimIndent()),
      summaryTable = listOf(
        Pair("Elementary", "Roll, push hard, and jump off a skateboard"),
        Pair("High School", "Inertia, F = ma, and Action-Reaction"),
        Pair("Undergraduate", "dp/dt momentum formulation and non-inertial reference frames"),
        Pair("PhD Level", "Lagrangian mechanics, stationary action, and Noether's theorem")
      ),
      examTrap = "Believing action and reaction forces cancel each other out; they act on two different bodies!"
    )
    articles["newton laws"] = articles["newton"]!!

    articles["gravity"] = MultiLevelAcademicArticle(
      title = "Gravity: From Universal Gravitation to Spacetime Curvature",
      domain = "Physics (Gravitation & Relativity)",
      thesis = "The fundamental interaction causing mutual attraction between mass-energy, modeled in Newtonian physics as a force and in General Relativity as the curvature of spacetime.",
      elementary = "Imagine a heavy bowling ball sitting on a soft trampoline mattress! It makes a deep bowl in the middle, so when you roll a little marble nearby, it curves right towards the bowling ball!",
      highSchool = "Newton's Law of Universal Gravitation: F = G * (m1 * m2) / r^2. Gravity attracts all masses across the universe with an inverse-square law. On Earth's surface, acceleration g ≈ 9.8 m/s^2.",
      undergraduate = "Einstein's Equivalence Principle: inertial mass equals gravitational mass; an observer in a closed elevator cannot distinguish between gravity and uniform acceleration. Gravitational time dilation: clocks tick slower in deeper gravitational potentials.",
      phdResearch = "Einstein Field Equations: G_μν + Λ * g_μν = (8πG / c^4) * T_μν, relating spacetime metric curvature tensor to the stress-energy tensor. Predicts black hole event horizons (Schwarzschild metric), frame-dragging (Kerr metric), and gravitational wave quadrupole radiation.",
      formulaOrCode = Pair("text", """
        G_μν = (8πG / c^4) * T_μν
      """.trimIndent()),
      summaryTable = listOf(
        Pair("Elementary", "Heavy bowling ball making a curve on a bouncy trampoline"),
        Pair("High School", "Newton's F = G(m1*m2)/r^2 inverse-square law"),
        Pair("Undergraduate", "Equivalence principle and gravitational time dilation"),
        Pair("PhD Level", "Einstein field equations, metric tensors, and gravitational waves")
      ),
      examTrap = "Thinking astronauts in orbit experience zero gravity because of distance; they are in continuous freefall."
    )
    articles["general relativity"] = articles["gravity"]!!

    articles["quantum"] = MultiLevelAcademicArticle(
      title = "Quantum Mechanics: Superposition & Wave-Particle Duality",
      domain = "Physics (Quantum Physics)",
      thesis = "The fundamental physical theory describing nature at the scale of atoms and subatomic particles, where energy and physical quantities are quantized.",
      elementary = "Imagine a coin spinning super fast on a table. While it's spinning, is it Heads or Tails? It's kind of BOTH at the exact same time until you slam your hand down to stop it!",
      highSchool = "Energy comes in packets called quanta (photons: E = h * f). Particles exhibit wave-particle duality (de Broglie: λ = h / p). Heisenberg Uncertainty Principle: cannot measure position and momentum simultaneously: Δx * Δp >= h / (4π).",
      undergraduate = "System states are vectors in a complex Hilbert space |ψ>. Time evolution follows the Schrödinger Equation: i * ħ * ∂ψ/∂t = H * ψ. Born Rule: measurement probability density is |ψ(x)|^2. Superposition collapses upon interaction.",
      phdResearch = "The Measurement Problem and interpretation: Copenhagen vs Many-Worlds (Everett) vs Quantum Decoherence. Bell's Theorem proves that no local hidden variable theory can reproduce quantum mechanical predictions. Relativistic Quantum Field Theory (QFT) unifies quantum mechanics with special relativity.",
      formulaOrCode = Pair("text", """
        i*ħ * ∂|ψ(t)>/∂t = H|ψ(t)>
      """.trimIndent()),
      summaryTable = listOf(
        Pair("Elementary", "A spinning coin that is both Heads and Tails until you stop it"),
        Pair("High School", "Energy quanta, de Broglie waves, Heisenberg uncertainty"),
        Pair("Undergraduate", "Schrödinger equation, Hilbert state vectors, Born rule"),
        Pair("PhD Level", "Quantum decoherence, Bell's theorem, and Quantum Field Theory")
      ),
      examTrap = "Believing the uncertainty principle is caused by clumsy measuring tools; it is an inherent wave property."
    )
    articles["quantum mechanics"] = articles["quantum"]!!

    articles["entropy"] = MultiLevelAcademicArticle(
      title = "Entropy & Second Law of Thermodynamics",
      domain = "Physics (Thermodynamics & Statistical Mechanics)",
      thesis = "A measure of the microscopic disorder and unavailable energy in a physical system, dictating the thermodynamic arrow of time.",
      elementary = "Why is it super easy to drop an egg and make a giant messy splatter, but nobody can un-drop the egg and put the shell back together? Because things in the universe love getting messy!",
      highSchool = "The Second Law of Thermodynamics: In an isolated system, entropy always increases or stays constant over time. Heat naturally flows from hot objects to cold objects, never the reverse.",
      undergraduate = "Statistical definition: Boltzmann's equation S = k_B * ln(W), where W is the number of microstates. Thermodynamic definition: dS = dQ_rev / T. Carnot cycle maximum thermal efficiency: η = 1 - T_C / T_H.",
      phdResearch = "Non-equilibrium fluctuation theorems (Jarzynski equality, Crooks theorem). Landauer's Principle: logically irreversible computation (erasing 1 bit) dissipates at minimum k_B * T * ln(2) of heat. The Black Hole Information Paradox and Bekenstein-Hawking entropy: S_BH = (k_B * c^3 * A) / (4 * G * ħ).",
      formulaOrCode = Pair("text", """
        S = k_B * ln(W)   and   dS >= dQ / T
      """.trimIndent()),
      summaryTable = listOf(
        Pair("Elementary", "You can scramble an egg, but you can't unscramble it"),
        Pair("High School", "Entropy always increases; heat flows hot to cold"),
        Pair("Undergraduate", "Boltzmann S = k ln W microstates and Carnot efficiency"),
        Pair("PhD Level", "Landauer's principle, fluctuation theorems, Bekenstein-Hawking entropy")
      ),
      examTrap = "Thinking living organisms violate the 2nd Law; organisms are open systems dissipating entropy to surroundings."
    )

    // -------------------------------------------------------------
    // BIOLOGY & MEDICINE
    // -------------------------------------------------------------
    articles["photosynthesis"] = MultiLevelAcademicArticle(
      title = "Photosynthesis: Photobiochemical Energy Transduction",
      domain = "Biology (Biochemistry & Plant Physiology)",
      thesis = "The biochemical process by which photoautotrophs convert light energy into chemical energy stored in carbohydrates, releasing molecular oxygen.",
      elementary = "Plants have tiny green solar panels inside their leaves! They catch warm sunshine, drink water through their roots, breathe in air, and bake sweet food for energy!",
      highSchool = "Chemical Equation: 6 CO2 + 6 H2O + light -> C6H12O6 + 6 O2. Light reactions in chloroplast thylakoids produce ATP and NADPH. Dark reactions (Calvin cycle) in the stroma use RuBisCO to synthesize glucose.",
      undergraduate = "Z-scheme electron transport: Photosystem II absorbs photons (P680), splits water via the oxygen-evolving Mn4CaO5 complex, generating a proton gradient across the thylakoid membrane that drives ATP Synthase. Photosystem I (P700) reduces NADP+ to NADPH.",
      phdResearch = "Quantum coherence in light-harvesting pigment-protein complexes (Fenna-Matthews-Olson complex) yielding near 100% excitation transfer efficiency. Photorespiration inefficiencies when RuBisCO oxygenates ribulose 1,5-bisphosphate, driving evolution of C4 Kranz anatomy and synthetic carbon-fixation bypasses.",
      formulaOrCode = Pair("text", """
        6 CO2 + 6 H2O + photons -> C6H12O6 + 6 O2
        Thylakoid (Light) -> ATP + NADPH -> Stroma (Calvin Cycle / RuBisCO) -> Sugar
      """.trimIndent()),
      summaryTable = listOf(
        Pair("Elementary", "Plants using green solar panels to turn sunlight into food"),
        Pair("High School", "CO2 + H2O -> Glucose + O2 via light and dark reactions"),
        Pair("Undergraduate", "Z-scheme electron transport, water splitting, RuBisCO fixation"),
        Pair("PhD Level", "Quantum coherence in FMO complexes and C4/CAM adaptations")
      ),
      examTrap = "Believing the Calvin cycle only occurs at night; it requires daytime light reaction products."
    )

    articles["mitochondria"] = MultiLevelAcademicArticle(
      title = "Mitochondria: Oxidative Phosphorylation & Bioenergetics",
      domain = "Biology (Cell Biology & Metabolism)",
      thesis = "The double-membraned cellular organelle that generates the bulk of eukaryotic chemical energy in the form of Adenosine Triphosphate (ATP).",
      elementary = "The cellular battery pack and power station! It takes the food you eat and turns it into electric energy so your muscles can run and jump!",
      highSchool = "Known as the powerhouse of the cell. Aerobic cellular respiration takes place here: Krebs / Citric Acid cycle in the matrix and Electron Transport Chain (ETC) on the inner cristae membrane, producing ~30-32 ATP per glucose.",
      undergraduate = "Chemiosmotic Hypothesis (Peter Mitchell): Complexes I, III, and IV pump protons into the intermembrane space, establishing an electrochemical proton motive force (Δψ + ΔpH). Protons flow back through the F0F1 ATP Synthase rotary motor, phosphorylating ADP to ATP.",
      phdResearch = "Endosymbiotic Theory: mitochondria originated from engulfed alpha-proteobacteria, retaining circular mtDNA and 70S ribosomes. Mitochondrial dynamics (Mfn1/2 fusion vs Drp1 fission), mitochondrial reactive oxygen species (mROS) signaling, and the intrinsic apoptotic pathway triggered by Cytochrome c release.",
      formulaOrCode = Pair("text", """
        Glucose -> Glycolysis (Cytoplasm: 2 ATP) -> Krebs Cycle (Matrix: 2 ATP)
        -> Electron Transport Chain (Inner Membrane: ~28 ATP via ATP Synthase)
      """.trimIndent()),
      summaryTable = listOf(
        Pair("Elementary", "The tiny power battery inside every cell"),
        Pair("High School", "Cellular powerhouse making ATP via respiration"),
        Pair("Undergraduate", "Chemiosmotic proton gradient driving rotary ATP Synthase"),
        Pair("PhD Level", "Alpha-proteobacterial endosymbiosis and Cytochrome c apoptosis")
      ),
      examTrap = "Thinking glycolysis happens inside mitochondria; glycolysis occurs in the cytoplasm."
    )

    articles["dna"] = MultiLevelAcademicArticle(
      title = "DNA Structure & The Central Dogma of Molecular Biology",
      domain = "Biology (Genetics & Molecular Biology)",
      thesis = "A polymer of four deoxyribonucleotides forming a double helix that encodes the genetic instructions for biological development and functioning.",
      elementary = "A giant cookbook inside every single cell in your body! It tells your body what color eyes you have, how tall you grow, and how to build your heart and bones!",
      highSchool = "Double helix discovered by Watson, Crick, and Franklin. Made of 4 bases: Adenine (A), Thymine (T), Guanine (G), Cytosine (C). A pairs with T; G pairs with C. Central Dogma: DNA -> mRNA (Transcription) -> Protein (Translation).",
      undergraduate = "Semiconservative replication mediated by DNA Polymerase III, Helicase, Primase, and Okazaki fragments on the lagging strand. Triplet genetic code (64 codons for 20 amino acids). Epigenetics: DNA methylation and histone post-translational modifications regulate gene expression without altering nucleotide sequences.",
      phdResearch = "Chromatin conformation capture (Hi-C) revealing topologically associating domains (TADs). CRISPR-Cas9 ribonucleoprotein endonuclease mechanics producing targeted double-strand breaks repaired via Non-Homologous End Joining (NHEJ) vs Homology-Directed Repair (HDR). Base editing and prime editing bypassing double-strand breaks.",
      formulaOrCode = Pair("text", """
        DNA (Transcription) -> mRNA (Ribosome Translation) -> Functional Protein
        Base Pairing: [A = T] (2 H-bonds)  and  [G ≡ C] (3 H-bonds)
      """.trimIndent()),
      summaryTable = listOf(
        Pair("Elementary", "The master recipe book for your entire body"),
        Pair("High School", "Double helix with A-T and G-C base pairs; Central Dogma"),
        Pair("Undergraduate", "Semiconservative replication, Okazaki fragments, epigenetics"),
        Pair("PhD Level", "TAD chromatin architecture, CRISPR-Cas9 DSB repair kinetics")
      ),
      examTrap = "Forgetting that RNA replaces Thymine (T) with Uracil (U)."
    )

    articles["neuron"] = MultiLevelAcademicArticle(
      title = "Neuron Neurophysiology & Action Potentials",
      domain = "Medicine & Neuroscience",
      thesis = "Electrically excitable cells that process and transmit information through electrical impulses and chemical neurotransmitters across synapses.",
      elementary = "Tiny electric telephone wires inside your brain whispering secret messages to each other so fast you can think and wiggle your toes in a blink!",
      highSchool = "Neurons have dendrites (receive signals), a cell body, and an axon (sends signals). An electrical spike called an Action Potential travels down the axon to release neurotransmitters (like dopamine) across synapses to the next cell.",
      undergraduate = "Resting membrane potential (-70 mV) maintained by Na+/K+-ATPase pump (3 Na+ out, 2 K+ in). When threshold (-55 mV) is reached, voltage-gated Na+ channels rapidly open, causing depolarization (+40 mV). Inactivation of Na+ channels and opening of voltage-gated K+ channels causes repolarization and refractory periods.",
      phdResearch = "Hodgkin-Huxley mathematical model: non-linear system of differential equations quantifying voltage-dependent conductances (m, h, n gating variables). Synaptic plasticity: Long-Term Potentiation (LTP) mediated by NMDA and AMPA receptor trafficking and calcium-calmodulin kinase II (CaMKII) activation.",
      formulaOrCode = Pair("text", """
        Resting (-70mV) -> Threshold (-55mV) -> Na+ Influx (Depolarization +40mV)
        -> K+ Efflux (Repolarization) -> Hyperpolarization -> Refractory Period
      """.trimIndent()),
      summaryTable = listOf(
        Pair("Elementary", "Tiny electric wires whispering messages in your brain"),
        Pair("High School", "Axons, dendrites, action potentials, and neurotransmitters"),
        Pair("Undergraduate", "Voltage-gated Na+/K+ channels, resting -70mV, saltatory conduction"),
        Pair("PhD Level", "Hodgkin-Huxley equations, NMDA/AMPA receptor trafficking, LTP")
      ),
      examTrap = "Believing action potentials can vary in size; they follow the 'All-or-None' principle."
    )

    // -------------------------------------------------------------
    // ECONOMICS & COMMERCE
    // -------------------------------------------------------------
    articles["supply and demand"] = MultiLevelAcademicArticle(
      title = "Supply and Demand: Price Theory & Market Equilibrium",
      domain = "Economics (Microeconomics)",
      thesis = "An economic model of price determination in a competitive market based on the interaction between consumer demand and producer supply.",
      elementary = "If only 1 ice cream cone is left on a hot summer day and 10 kids want it, the price goes up! If a truck brings 1,000 ice creams and nobody wants them, the price drops to get people to buy!",
      highSchool = "Law of Demand (price rises -> quantity demanded falls) and Law of Supply (price rises -> quantity supplied rises). The point where both curves intersect is the Market Equilibrium price and quantity.",
      undergraduate = "Price Elasticity of Demand: Ed = (%ΔQ) / (%ΔP). Consumer and Producer surplus. Price ceilings cause shortages; price floors cause surpluses. Government taxation introduces Deadweight Loss (DWL) by distorting equilibrium output.",
      phdResearch = "Walrasian general equilibrium theory and the Arrow-Debreu model proving the existence of equilibrium prices via Brouwer and Kakutani fixed-point theorems. First and Second Fundamental Welfare Theorems under strict convexity and complete markets. Market failures under asymmetric information (Akerlof's lemons).",
      formulaOrCode = Pair("text", """
        Q_d(P) = Q_s(P)  ==>  P* = Equilibrium Price
      """.trimIndent()),
      summaryTable = listOf(
        Pair("Elementary", "Scarce ice cream gets expensive; too many ice creams get cheap"),
        Pair("High School", "Intersecting supply and demand curves at market equilibrium"),
        Pair("Undergraduate", "Elasticity, Consumer/Producer surplus, and Deadweight Loss"),
        Pair("PhD Level", "Arrow-Debreu general equilibrium, Kakutani fixed point theorem")
      ),
      examTrap = "Confusing a 'change in demand' (shift of whole curve) with a 'change in quantity demanded' (movement along curve)."
    )

    articles["inflation"] = MultiLevelAcademicArticle(
      title = "Inflation, Purchasing Power & Monetary Policy",
      domain = "Economics (Macroeconomics)",
      thesis = "A broad rise in the price level of goods and services across an economy, eroding the purchasing power of the national currency unit.",
      elementary = "If everyone in your classroom suddenly gets 1,000 gold toy coins, people will charge 50 coins for a pencil instead of 1 coin. The coins are worth less because there are so many of them!",
      highSchool = "Measured using the Consumer Price Index (CPI) basket. Demand-pull inflation (too much money chasing too few goods) vs Cost-push inflation (rising production costs). Central banks raise interest rates to slow inflation down.",
      undergraduate = "Quantity Theory of Money: M * V = P * Y (Money Supply × Velocity = Price Level × Real Output). The Phillips Curve describes the short-run trade-off between inflation and unemployment. Central bank interest rate transmission mechanism.",
      phdResearch = "Dynamic Stochastic General Equilibrium (DSGE) modeling with Calvo sticky price setting. The New Keynesian Phillips Curve: π_t = β * E_t[π_{t+1}] + κ * y_t. Zero Lower Bound (ZLB), forward guidance, quantitative easing (QE), and the Fiscal Theory of the Price Level (FTPL).",
      formulaOrCode = Pair("text", """
        M * V = P * Y  ==>  %ΔM + %ΔV ≈ %ΔP + %ΔY
      """.trimIndent()),
      summaryTable = listOf(
        Pair("Elementary", "Too many coins make prices go up and coins buy less"),
        Pair("High School", "CPI basket, demand-pull vs cost-push, interest rates"),
        Pair("Undergraduate", "M*V = P*Y quantity theory and short-run Phillips curve"),
        Pair("PhD Level", "New Keynesian DSGE, Calvo pricing, and Fiscal Theory of Price Level")
      ),
      examTrap = "Assuming inflation means the economy is collapsing; moderate 2% inflation is standard for economic growth."
    )

    // -------------------------------------------------------------
    // LAW & GOVERNANCE
    // -------------------------------------------------------------
    articles["contract"] = MultiLevelAcademicArticle(
      title = "Contract Law: Offer, Acceptance, Consideration & Breach",
      domain = "Law & Jurisprudence",
      thesis = "A legally binding agreement between parties creating mutual obligations enforceable by law.",
      elementary = "A promise between two friends: 'If you let me play with your crayons today, I will share my stickers tomorrow!' Both friends shake hands on it, so it's a fair promise they must keep!",
      highSchool = "Core requirements: 1. Offer, 2. Acceptance, 3. Consideration (something of value exchanged), 4. Mutual assent (meeting of minds), 5. Legal capacity. If a party breaks the deal, they commit a Breach of Contract.",
      undergraduate = "The Mailbox Rule: acceptance is effective upon dispatch, while revocation is effective upon receipt. Doctrine of Consideration: past consideration is invalid. Promissory Estoppel protects reasonable detrimental reliance. Remedies: Expectation damages, Reliance damages, and Specific Performance.",
      phdResearch = "Economic Analysis of Contract Law (Posner, Coase Theorem): Efficient Breach Hypothesis posits that breach of contract is economically desirable if the promisor's gain exceeds the promisee's loss. Incomplete Contract Theory (Hart-Moore): transaction costs prevent parties from specifying all future states, governing optimal ownership rights.",
      formulaOrCode = Pair("text", """
        Offer + Acceptance + Consideration (Value Exchanged) = Enforceable Contract
        Breach -> Remedy (Expectation Damages = Put plaintiff in position as if performed)
      """.trimIndent()),
      summaryTable = listOf(
        Pair("Elementary", "A fair promise and handshake between two friends"),
        Pair("High School", "Offer, Acceptance, Consideration, and Breach remedies"),
        Pair("Undergraduate", "Mailbox rule, promissory estoppel, expectation damages"),
        Pair("PhD Level", "Efficient breach hypothesis and Hart-Moore incomplete contracts")
      ),
      examTrap = "Believing that a gratuitous gift promise is an enforceable contract; it lacks Consideration."
    )
    articles["contract law"] = articles["contract"]!!

    // -------------------------------------------------------------
    // CURIOSITY QUESTIONS
    // -------------------------------------------------------------
    articles["why is the sky blue"] = MultiLevelAcademicArticle(
      title = "Atmospheric Optics: Rayleigh Scattering & Blue Sky",
      domain = "General Science & Optics",
      thesis = "The blue appearance of the daytime sky is caused by the wavelength-dependent elastic scattering of solar radiation by atmospheric gas molecules.",
      elementary = "Sunlight looks white, but it's made of all the rainbow colors mixed together! Blue light waves are tiny and bouncy, so when they hit the air in our sky, they bounce all around in every direction right into our eyes!",
      highSchool = "Rayleigh Scattering: Nitrogen and oxygen molecules in the air are much smaller than light wavelengths. Shorter wavelengths of light (blue and violet) scatter far more in all directions than longer wavelengths (red and yellow).",
      undergraduate = "Rayleigh scattering intensity is inversely proportional to the fourth power of the wavelength: I ∝ 1 / λ^4. Blue light (~450 nm) scatters ~10 times more intensely than red light (~700 nm). Violet scatters even more, but human eye cone photoreceptors (L, M, S) are far more sensitive to blue, and the solar spectrum has less violet.",
      phdResearch = "Classical derivation from Maxwell's equations: the oscillating electric field of incident radiation induces an electric dipole moment p = α * E in neutral gas molecules. The total scattering cross-section is σ_R = (8π/3) * k^4 * |α|^2. Contrast with Mie scattering by larger aerosols (r >= λ), which is wavelength-independent, causing clouds to appear white.",
      formulaOrCode = Pair("text", """
        I(λ) ∝ 1 / λ^4  ==>  I(450nm) / I(700nm) ≈ (700/450)^4 ≈ 5.86x scattering
      """.trimIndent()),
      summaryTable = listOf(
        Pair("Elementary", "Bouncy blue light waves bouncing around in the sky"),
        Pair("High School", "Rayleigh scattering of short wavelengths by air molecules"),
        Pair("Undergraduate", "1/λ^4 power law and human trichromatic cone vision"),
        Pair("PhD Level", "Dipole radiation from induced polarizability and Mie scattering contrast")
      ),
      examTrap = "Assuming the sky is blue because it reflects the ocean; it is atmospheric molecular scattering."
    )
    articles["blue sky"] = articles["why is the sky blue"]!!

    articles["how do planes fly"] = MultiLevelAcademicArticle(
      title = "Aerodynamics: Wing Lift Generation & Fluid Dynamics",
      domain = "Physics & Aerospace Engineering",
      thesis = "Aircraft generate aerodynamic lift through the dynamic downward deflection of airflow produced by wing camber and angle of attack, creating pressure differentials.",
      elementary = "Stick your hand out of a moving car window and tilt your palm upward! The rushing wind pushes your hand up into the air! Airplane wings are shaped so the air pushes the whole plane up!",
      highSchool = "Airplane wings (airfoils) deflect moving air downward. By Newton's Third Law, deflecting air downward pushes the wing upward. Simultaneously, air moves faster over the curved top, creating lower pressure above the wing (Bernoulli's Principle).",
      undergraduate = "Lift equation: L = (1/2) * C_L * ρ * v^2 * A, where C_L is the lift coefficient, ρ is air density, v is true airspeed, and A is wing area. Lift is primarily generated by circulation Γ around the airfoil dictated by the Kutta-Joukowski theorem: L' = ρ * v * Γ.",
      phdResearch = "Navier-Stokes equations for viscous fluid flow with boundary layer theory (Prandtl). Adverse pressure gradients cause boundary layer separation leading to aerodynamic stall. Computational Fluid Dynamics (CFD) models turbulence via Reynolds-Averaged Navier-Stokes (RANS) and Large Eddy Simulation (LES).",
      formulaOrCode = Pair("text", """
        L = (1/2) * C_L * ρ * v^2 * A   and   L' = ρ_∞ * V_∞ * Γ
      """.trimIndent()),
      summaryTable = listOf(
        Pair("Elementary", "Tilting your hand out of a moving car window"),
        Pair("High School", "Downward air deflection (Newton) + pressure difference (Bernoulli)"),
        Pair("Undergraduate", "Lift equation and Kutta-Joukowski circulation theorem"),
        Pair("PhD Level", "Navier-Stokes equations, boundary layer separation, and CFD")
      ),
      examTrap = "Believing the debunked 'Equal Transit Time' fallacy; air over the top reaches the trailing edge much faster."
    )

    // -------------------------------------------------------------
    // GRAPH ALGORITHMS & CORE DATA STRUCTURES
    // -------------------------------------------------------------
    articles["bellman ford"] = MultiLevelAcademicArticle(
      title = "Bellman-Ford Algorithm: Shortest Paths with Negative Weights",
      domain = "Computer Science (Graph Algorithms)",
      thesis = "Bellman-Ford is a single-source shortest path algorithm that finds shortest paths from a source vertex to all vertices in a weighted graph—supporting negative edge weights and detecting negative-weight cycles.",
      elementary = "Imagine checking every road sign across a city 4 times in a row to see if any shortcut lowers your travel time, then checking one extra time: if your trip still gets shorter forever, there is a magic infinite-loop shortcut!",
      highSchool = "Unlike Dijkstra (which is greedy and fails on negative weights), Bellman-Ford relaxes every edge in the graph V - 1 times (where V is the number of vertices). After V - 1 passes, any edge that can still be relaxed proves a reachable negative-weight cycle exists.",
      undergraduate = "Time Complexity: O(V * E) across V - 1 relaxation phases over E edges; Space Complexity: O(V). Step 1: Initialize dist[source] = 0, others = ∞. Step 2: Repeat V - 1 times: for each edge (u, v, w), if dist[u] + w < dist[v], update dist[v] = dist[u] + w. Step 3: Run one V-th pass; if any edge relaxes, report a negative cycle.",
      phdResearch = "Bellman-Ford implements dynamic programming over path hop counts: after k passes, dist[v] equals the shortest path using at most k edges. Shortest Path Faster Algorithm (SPFA) optimizes average-case runtime with a FIFO relaxation queue, while Johnson's algorithm pairs Bellman-Ford reweighting with all-pairs Dijkstra.",
      formulaOrCode = Pair("python", """
        def bellman_ford(vertices, edges, source):
            dist = {v: float('inf') for v in vertices}
            dist[source] = 0

            # Relax all edges V - 1 times
            for _ in range(len(vertices) - 1):
                for u, v, w in edges:
                    if dist[u] != float('inf') and dist[u] + w < dist[v]:
                        dist[v] = dist[u] + w

            # Check V-th iteration for negative-weight cycles
            for u, v, w in edges:
                if dist[u] != float('inf') and dist[u] + w < dist[v]:
                    raise ValueError("Graph contains a negative-weight cycle")

            return dist
      """.trimIndent()),
      summaryTable = listOf(
        Pair("Elementary", "Checking every road shortcut V - 1 rounds to find negative loops"),
        Pair("High School", "Relaxes all edges V - 1 times; handles negative edge weights"),
        Pair("Undergraduate", "O(V * E) time, O(V) space, V-th pass negative cycle check"),
        Pair("PhD Level", "Hop-bounded DP invariant, SPFA optimization, Johnson's reweighting")
      ),
      examTrap = "Using Dijkstra on graphs with negative edge weights instead of Bellman-Ford, or stopping after 1 pass."
    )
    articles["bellman ford algorithm"] = articles["bellman ford"]!!
    articles["bellman"] = articles["bellman ford"]!!

    articles["dijkstra"] = MultiLevelAcademicArticle(
      title = "Dijkstra's Algorithm: Greedy Single-Source Shortest Path",
      domain = "Computer Science (Graph Algorithms)",
      thesis = "Dijkstra's algorithm finds the shortest paths from a single source vertex to all reachable vertices in a graph with non-negative edge weights using a greedy min-priority queue.",
      elementary = "Starting from your home, always walk to the closest unvisited house next, update your map of distances to its neighbors, and lock each visited house once you reach it!",
      highSchool = "Maintains a tentative distance table and repeatedly picks the unvisited vertex u with minimum distance, relaxing every outgoing edge (u, v). Requires all edge weights w >= 0.",
      undergraduate = "Time Complexity: O((V + E) log V) with a binary min-heap PriorityQueue, or O(E + V log V) with a Fibonacci heap. Space Complexity: O(V + E). Greedy choice relies on path cost being monotonically non-decreasing.",
      phdResearch = "A* generalizes Dijkstra with an admissible consistent heuristic h(v) guiding search priority f(v) = g(v) + h(v). Bidirectional Dijkstra and Contraction Hierarchies achieve sub-millisecond continental road routing.",
      formulaOrCode = Pair("python", """
        import heapq

        def dijkstra(graph, source):
            dist = {source: 0}
            pq = [(0, source)]
            while pq:
                d, u = heapq.heappop(pq)
                if d > dist.get(u, float('inf')):
                    continue
                for v, w in graph.get(u, []):
                    if d + w < dist.get(v, float('inf')):
                        dist[v] = d + w
                        heapq.heappush(pq, (dist[v], v))
            return dist
      """.trimIndent()),
      summaryTable = listOf(
        Pair("Elementary", "Always visit the closest unvisited neighbor next"),
        Pair("High School", "Greedy edge relaxation for non-negative edge weights"),
        Pair("Undergraduate", "O((V + E) log V) with binary min-heap"),
        Pair("PhD Level", "A* consistent heuristics and contraction hierarchies")
      ),
      examTrap = "Applying Dijkstra to graphs containing negative edge weights, where finalized nodes miss shorter paths."
    )
    articles["dijkstra algorithm"] = articles["dijkstra"]!!

    articles["bfs"] = MultiLevelAcademicArticle(
      title = "Breadth-First Search (BFS): Level-Order Graph Traversal",
      domain = "Computer Science (Graph Algorithms)",
      thesis = "Breadth-First Search explores a graph layer by layer using a FIFO Queue, guaranteeing the shortest path in terms of edge count in unweighted graphs.",
      elementary = "Drop a pebble in a pond and watch the ripples spread ring by ring outward from the center—BFS visits everyone 1 hop away, then 2 hops away, then 3 hops away!",
      highSchool = "Starts at root/source, marks it visited, pushes it to a Queue, and repeatedly dequeues a node to enqueue all unvisited neighbors. Complexity: O(V + E) time and O(V) space.",
      undergraduate = "Maintains visited sets to handle cycles in general graphs. Discovers shortest paths in unweighted graphs in O(V + E) time and builds a shortest-path BFS tree.",
      phdResearch = "Bidirectional BFS reduces state-space expansion from O(b^d) to O(b^(d/2)). Multi-source BFS solves minimum multi-target grid distances cleanly.",
      formulaOrCode = Pair("python", """
        from collections import deque

        def bfs(graph, start):
            visited = {start}
            queue = deque([start])
            order = []
            while queue:
                u = queue.popleft()
                order.append(u)
                for v in graph.get(u, []):
                    if v not in visited:
                        visited.add(v)
                        queue.append(v)
            return order
      """.trimIndent()),
      summaryTable = listOf(
        Pair("Elementary", "Spreading ripples ring by ring from the start"),
        Pair("High School", "Queue-based level-order traversal in O(V + E)"),
        Pair("Undergraduate", "Unweighted shortest path guarantee & BFS tree"),
        Pair("PhD Level", "Bidirectional BFS and frontier parallelization")
      ),
      examTrap = "Assuming BFS gives the minimum-cost path in weighted graphs (use Dijkstra or Bellman-Ford instead)."
    )
    articles["breadth first search"] = articles["bfs"]!!

    articles["dfs"] = MultiLevelAcademicArticle(
      title = "Depth-First Search (DFS): Backtracking & Topological Order",
      domain = "Computer Science (Graph Algorithms)",
      thesis = "Depth-First Search explores as deep as possible along each branch before backtracking, using recursion or an explicit LIFO Stack in O(V + E) time.",
      elementary = "Walking through a hedge maze by always taking the first unvisited turn until you hit a dead end, then stepping back to try the previous turn!",
      highSchool = "Uses a Stack (or call stack recursion) to traverse deep paths. Essential for cycle detection, connected components, and topological sorting of DAGs.",
      undergraduate = "Tracks discovery/finish timestamps to classify Tree, Back, Forward, and Cross edges. Tarjan's and Kosaraju's algorithms compute Strongly Connected Components in O(V + E).",
      phdResearch = "Tarjan's low-link invariants identify bridges and articulation points in single-pass O(V + E) linear time for network reliability analysis.",
      formulaOrCode = Pair("python", """
        def dfs(graph, u, visited=None):
            if visited is None:
                visited = set()
            visited.add(u)
            for v in graph.get(u, []):
                if v not in visited:
                    dfs(graph, v, visited)
            return visited
      """.trimIndent()),
      summaryTable = listOf(
        Pair("Elementary", "Following one maze path to a dead end before backtracking"),
        Pair("High School", "Stack/recursion traversal in O(V + E) time"),
        Pair("Undergraduate", "Cycle detection, topological sort, edge classification"),
        Pair("PhD Level", "Tarjan SCCs and articulation point low-link invariants")
      ),
      examTrap = "Assuming DFS always finds the shortest path in an unweighted graph."
    )
    articles["depth first search"] = articles["dfs"]!!
  }
}
