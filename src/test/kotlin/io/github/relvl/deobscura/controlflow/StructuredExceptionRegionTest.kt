package io.github.relvl.deobscura.controlflow

import io.github.relvl.deobscura.analysis.SsaControlFlowGraph
import io.github.relvl.deobscura.analysis.ValueId
import io.github.relvl.deobscura.cfg.BasicBlockId
import io.github.relvl.deobscura.cfg.ControlFlowEdgeKind
import io.github.relvl.deobscura.expression.ExpressionAnalysis
import io.github.relvl.deobscura.expression.ExpressionStatement
import kotlin.test.*

class StructuredExceptionRegionTest {
    private val analyzer = StructuredControlFlowAnalyzer()

    // Pseudocode: try { BODY } catch (A e) { A } catch (B e) { B }
    @Test
    fun `recognizes sibling typed catches for one protected range`() {
        val b0 = BasicBlockId(0)
        val b1 = BasicBlockId(1)
        val b2 = BasicBlockId(2)
        val b3 = BasicBlockId(3)
        val edges = listOf(
            edge(b0, b3, ControlFlowEdgeKind.FALLTHROUGH),
            exceptionEdge(b0, b1, "java/io/IOException"),
            exceptionEdge(b0, b2, "java/lang/RuntimeException"),
            edge(b1, b3, ControlFlowEdgeKind.JUMP),
        )
        val graph = exceptionGraph(
            blockCount = 4,
            edges = edges,
            handlers = listOf(
                exceptionHandler(0, 1, 1, "java/io/IOException"),
                exceptionHandler(0, 1, 2, "java/lang/RuntimeException"),
            ),
        )
        val result = analyzer.analyze(
            graph,
            SsaControlFlowGraph(setOf(b0, b1, b2, b3), edges, b0),
            ExpressionAnalysis(emptyMap(), listOf(ExpressionStatement.Return(2, null))),
        )

        val region = assertIs<StructuredRegion.TryCatch>(result.regions.single())
        assertEquals(b0, region.header)
        assertEquals(setOf(b0), region.tryBlocks)
        assertEquals(b3, region.continuation)
        assertEquals(2, region.catches.size)
        assertEquals(listOf("java/io/IOException"), region.catches.single { it.entry == b1 }.catchTypes)
        assertEquals(listOf("java/lang/RuntimeException"), region.catches.single { it.entry == b2 }.catchTypes)
        assertEquals(1, result.exceptionRegionCount)
        assertEquals(0, result.unstructuredExceptionRegionCount)
    }

    // A terminal-continuation if must not absorb normal flow beyond a structured exception boundary.
    @Test
    fun `terminal continuation if does not cross a structured catch region`() {
        val header = BasicBlockId(0)
        val protectedArm = BasicBlockId(1)
        val terminalContinuation = BasicBlockId(2)
        val protectedTail = BasicBlockId(3)
        val afterTry = BasicBlockId(4)
        val terminalSideExit = BasicBlockId(5)
        val catchEntry = BasicBlockId(6)
        val edges = listOf(
            edge(header, terminalContinuation, ControlFlowEdgeKind.CONDITIONAL),
            edge(header, protectedArm, ControlFlowEdgeKind.FALLTHROUGH),
            exceptionEdge(header, catchEntry, "java/io/IOException"),
            edge(protectedArm, terminalContinuation, ControlFlowEdgeKind.CONDITIONAL),
            edge(protectedArm, protectedTail, ControlFlowEdgeKind.FALLTHROUGH),
            exceptionEdge(protectedArm, catchEntry, "java/io/IOException"),
            edge(protectedTail, afterTry, ControlFlowEdgeKind.FALLTHROUGH),
            exceptionEdge(protectedTail, catchEntry, "java/io/IOException"),
            edge(afterTry, terminalSideExit, ControlFlowEdgeKind.FALLTHROUGH),
        )
        val graph = exceptionGraph(
            blockCount = 7,
            edges = edges,
            handlers = listOf(exceptionHandler(0, 4, 6, "java/io/IOException")),
        )
        val result = analyzer.analyze(
            graph,
            SsaControlFlowGraph(
                setOf(header, protectedArm, terminalContinuation, protectedTail, afterTry, terminalSideExit, catchEntry),
                edges,
                header,
            ),
            ExpressionAnalysis(
                emptyMap(),
                listOf(
                    branch(0),
                    // Keep the protected arm source-visible so the short-circuit pass cannot fold
                    // it into the header before terminal-continuation recognition runs.
                    ExpressionStatement.Raw(1, "side-effect", emptyList()),
                    branch(1),
                    ExpressionStatement.Return(2, null),
                    ExpressionStatement.Return(5, null),
                    ExpressionStatement.Return(6, null),
                ),
            ),
        )

        val exceptionRegion = result.regions.filterIsInstance<StructuredRegion.TryCatch>().single()
        assertEquals(afterTry, exceptionRegion.continuation)
        assertTrue(result.regions.filterIsInstance<StructuredRegion.If>().none { it.header == header })
    }

    // Pseudocode: try { if (...) return; BODY } catch (E e) { ... }
    @Test
    fun `coalesces source try split around terminal control transfer`() {
        val b0 = BasicBlockId(0)
        val b1 = BasicBlockId(1)
        val b2 = BasicBlockId(2)
        val b3 = BasicBlockId(3)
        val b4 = BasicBlockId(4)
        val edges = listOf(
            edge(b0, b2, ControlFlowEdgeKind.CONDITIONAL),
            edge(b0, b1, ControlFlowEdgeKind.FALLTHROUGH),
            exceptionEdge(b0, b4, "java/io/IOException"),
            edge(b2, b3, ControlFlowEdgeKind.FALLTHROUGH),
            exceptionEdge(b2, b4, "java/io/IOException"),
        )
        val graph = exceptionGraph(
            blockCount = 5,
            edges = edges,
            handlers = listOf(
                exceptionHandler(0, 1, 4, "java/io/IOException"),
                exceptionHandler(2, 3, 4, "java/io/IOException"),
            ),
        )
        val result = analyzer.analyze(
            graph,
            SsaControlFlowGraph(setOf(b0, b1, b2, b3, b4), edges, b0),
            ExpressionAnalysis(
                emptyMap(),
                listOf(
                    ExpressionStatement.Return(1, null),
                    ExpressionStatement.Return(4, null),
                ),
            ),
        )

        val region = assertIs<StructuredRegion.TryCatch>(result.regions.single())
        assertEquals(setOf(b0, b1, b2), region.tryBlocks)
        assertEquals(b3, region.continuation)
        assertEquals(
            listOf(
                StructuredProtectedRange(0, 1),
                StructuredProtectedRange(2, 3),
            ),
            region.protectedRanges,
        )
        assertEquals(1, result.exceptionRegionCount)
        assertEquals(0, result.unstructuredExceptionRegionCount)
    }

    // Pseudocode: try { split physical ranges } catch (A e) { ... } catch (B e) { ... }
    @Test
    fun `coalesced physical ranges retain one typed scope with sibling catches`() {
        val b0 = BasicBlockId(0)
        val b1 = BasicBlockId(1)
        val b2 = BasicBlockId(2)
        val continuation = BasicBlockId(3)
        val ioCatch = BasicBlockId(4)
        val runtimeCatch = BasicBlockId(5)
        val edges = listOf(
            edge(b0, b2, ControlFlowEdgeKind.CONDITIONAL),
            edge(b0, b1, ControlFlowEdgeKind.FALLTHROUGH),
            exceptionEdge(b0, ioCatch, "java/io/IOException"),
            exceptionEdge(b0, runtimeCatch, "java/lang/RuntimeException"),
            edge(b2, continuation, ControlFlowEdgeKind.FALLTHROUGH),
            exceptionEdge(b2, ioCatch, "java/io/IOException"),
            exceptionEdge(b2, runtimeCatch, "java/lang/RuntimeException"),
            edge(ioCatch, continuation, ControlFlowEdgeKind.JUMP),
        )
        val graph = exceptionGraph(
            blockCount = 6,
            edges = edges,
            handlers = listOf(
                exceptionHandler(0, 1, 4, "java/io/IOException"),
                exceptionHandler(0, 1, 5, "java/lang/RuntimeException"),
                exceptionHandler(2, 3, 4, "java/io/IOException"),
                exceptionHandler(2, 3, 5, "java/lang/RuntimeException"),
            ),
        )
        val result = analyzer.analyze(
            graph,
            SsaControlFlowGraph(setOf(b0, b1, b2, continuation, ioCatch, runtimeCatch), edges, b0),
            ExpressionAnalysis(
                emptyMap(),
                listOf(
                    ExpressionStatement.Return(1, null),
                    ExpressionStatement.Return(5, null),
                ),
            ),
        )

        val region = assertIs<StructuredRegion.TryCatch>(result.regions.single())
        assertEquals(setOf(b0, b1, b2), region.tryBlocks)
        assertEquals(continuation, region.continuation)
        assertEquals(2, region.catches.size)
        assertEquals(listOf("java/io/IOException"), region.catches.single { it.entry == ioCatch }.catchTypes)
        assertEquals(listOf("java/lang/RuntimeException"), region.catches.single { it.entry == runtimeCatch }.catchTypes)
        assertEquals(
            listOf(
                StructuredProtectedRange(0, 1),
                StructuredProtectedRange(2, 3),
            ),
            region.protectedRanges,
        )
        assertEquals(1, result.exceptionRegionCount)
        assertEquals(0, result.unstructuredExceptionRegionCount)
    }

    // Pseudocode: try { try { ... } catch (...) {} } catch (...) {}  // crossing ranges rejected
    @Test
    fun `builds exception scope nesting and reports crossing scopes`() {
        val outer = setOf(BasicBlockId(0), BasicBlockId(1), BasicBlockId(2), BasicBlockId(3))
        val inner = setOf(BasicBlockId(1), BasicBlockId(2))
        val leaf = setOf(BasicBlockId(2))
        val sibling = setOf(BasicBlockId(4))

        val laminar = buildExceptionScopeNesting(listOf(outer, inner, leaf, sibling)) { it }

        assertTrue(laminar.isLaminar)
        assertEquals(outer, laminar.parentByScope[inner])
        assertEquals(inner, laminar.parentByScope[leaf])
        assertEquals(listOf(inner), laminar.childrenByScope[outer])
        assertEquals(listOf(leaf), laminar.childrenByScope[inner])
        assertNull(laminar.parentByScope[sibling])

        val crossingLeft = setOf(BasicBlockId(10), BasicBlockId(11))
        val crossingRight = setOf(BasicBlockId(11), BasicBlockId(12))
        val crossing = buildExceptionScopeNesting(listOf(crossingLeft, crossingRight)) { it }

        assertFalse(crossing.isLaminar)
        assertEquals(listOf(crossingLeft to crossingRight), crossing.crossingPairs)
    }

    // Pseudocode: try { try { BODY } catch (A e) {} } catch (B e) {} AFTER
    @Test
    fun `reconstructs nested typed catch scopes through a shared continuation`() {
        val outerTry = BasicBlockId(0)
        val outerCatch = BasicBlockId(1)
        val nestedCatch = BasicBlockId(2)
        val continuation = BasicBlockId(3)
        val edges = listOf(
            edge(outerTry, continuation, ControlFlowEdgeKind.FALLTHROUGH),
            exceptionEdge(outerTry, outerCatch, "java/io/IOException"),
            edge(outerCatch, continuation, ControlFlowEdgeKind.JUMP),
            exceptionEdge(outerCatch, nestedCatch, "java/lang/RuntimeException"),
            edge(nestedCatch, continuation, ControlFlowEdgeKind.JUMP),
        )
        val graph = exceptionGraph(
            blockCount = 4,
            edges = edges,
            handlers = listOf(
                exceptionHandler(0, 1, 1, "java/io/IOException"),
                exceptionHandler(1, 2, 2, "java/lang/RuntimeException"),
            ),
        )
        val result = analyzer.analyze(
            graph,
            SsaControlFlowGraph(setOf(outerTry, outerCatch, nestedCatch, continuation), edges, outerTry),
            ExpressionAnalysis(emptyMap(), listOf(ExpressionStatement.Return(3, null))),
        )

        val outer = result.regions.filterIsInstance<StructuredRegion.TryCatch>().single { it.header == outerTry }
        assertEquals(setOf(outerTry), outer.tryBlocks)
        assertEquals(setOf(outerCatch, nestedCatch), outer.catches.single().blocks)
        assertEquals(continuation, outer.continuation)

        val nested = result.regions.filterIsInstance<StructuredRegion.TryCatch>().single { it.header == outerCatch }
        assertEquals(setOf(outerCatch), nested.tryBlocks)
        assertEquals(setOf(nestedCatch), nested.catches.single().blocks)
        assertEquals(continuation, nested.continuation)
        assertEquals(0, result.unstructuredExceptionRegionCount)
    }

    // Pseudocode: try { ... } catch (A e) { try { ... } catch (B e) { ... } }
    @Test
    fun `closes catch ownership over recursively nested exception regions`() {
        val outerTry = BasicBlockId(0)
        val outerCatch = BasicBlockId(1)
        val nestedCatch = BasicBlockId(2)
        val deepCatch = BasicBlockId(3)
        val continuation = BasicBlockId(4)
        val edges = listOf(
            edge(outerTry, continuation, ControlFlowEdgeKind.FALLTHROUGH),
            exceptionEdge(outerTry, outerCatch, "java/io/IOException"),
            edge(outerCatch, continuation, ControlFlowEdgeKind.JUMP),
            exceptionEdge(outerCatch, nestedCatch, "java/lang/RuntimeException"),
            edge(nestedCatch, continuation, ControlFlowEdgeKind.JUMP),
            exceptionEdge(nestedCatch, deepCatch, "java/lang/IllegalStateException"),
            edge(deepCatch, continuation, ControlFlowEdgeKind.JUMP),
        )
        val graph = exceptionGraph(
            blockCount = 5,
            edges = edges,
            handlers = listOf(
                exceptionHandler(0, 1, 1, "java/io/IOException"),
                exceptionHandler(1, 2, 2, "java/lang/RuntimeException"),
                exceptionHandler(2, 3, 3, "java/lang/IllegalStateException"),
            ),
        )
        val result = analyzer.analyze(
            graph,
            SsaControlFlowGraph(setOf(outerTry, outerCatch, nestedCatch, deepCatch, continuation), edges, outerTry),
            ExpressionAnalysis(emptyMap(), listOf(ExpressionStatement.Return(4, null))),
        )

        val regions = result.regions.filterIsInstance<StructuredRegion.TryCatch>()
        val outer = regions.single { it.header == outerTry }
        assertEquals(setOf(outerCatch, nestedCatch, deepCatch), outer.catches.single().blocks)
        assertEquals(continuation, outer.continuation)

        val nested = regions.single { it.header == outerCatch }
        assertEquals(setOf(nestedCatch, deepCatch), nested.catches.single().blocks)
        assertEquals(continuation, nested.continuation)

        val deep = regions.single { it.header == nestedCatch }
        assertEquals(setOf(deepCatch), deep.catches.single().blocks)
        assertEquals(continuation, deep.continuation)
        assertEquals(0, result.unstructuredExceptionRegionCount)
    }

    // Pseudocode: try { ... } catch (A e) { try { ... } catch (B e) { ... } AFTER_IN_CATCH }
    @Test
    fun `treats nested catch body as owned by enclosing catch`() {
        val b0 = BasicBlockId(0)
        val b1 = BasicBlockId(1)
        val b2 = BasicBlockId(2)
        val b3 = BasicBlockId(3)
        val b4 = BasicBlockId(4)
        val b5 = BasicBlockId(5)
        val edges = listOf(
            edge(b0, b1, ControlFlowEdgeKind.FALLTHROUGH),
            exceptionEdge(b0, b2, "java/io/IOException"),
            edge(b2, b3, ControlFlowEdgeKind.FALLTHROUGH),
            edge(b3, b5, ControlFlowEdgeKind.JUMP),
            exceptionEdge(b3, b4, "java/io/IOException"),
            edge(b4, b5, ControlFlowEdgeKind.FALLTHROUGH),
        )
        val graph = exceptionGraph(
            blockCount = 6,
            edges = edges,
            handlers = listOf(
                exceptionHandler(0, 1, 2, "java/io/IOException"),
                exceptionHandler(3, 4, 4, "java/io/IOException"),
            ),
        )
        val result = analyzer.analyze(
            graph,
            SsaControlFlowGraph(setOf(b0, b1, b2, b3, b4, b5), edges, b0),
            ExpressionAnalysis(emptyMap(), listOf(ExpressionStatement.Throw(5, ValueId(0)))),
        )

        val outer = result.regions.filterIsInstance<StructuredRegion.TryCatch>().single { it.header == b0 }
        assertEquals(setOf(b2, b3, b4, b5), outer.catches.single().blocks)
        assertEquals(0, result.unstructuredExceptionRegionCount)
    }

    // Pseudocode: try { ... } catch (A|B e) { ... }  // unrelated handler peer is not allowed
    @Test
    fun `handler peer entry allowance is local to one typed catch scope`() {
        val catchEntry = BasicBlockId(10)
        val siblingHandler = BasicBlockId(11)
        val unrelatedHandler = BasicBlockId(12)
        val catchInterior = BasicBlockId(13)

        assertTrue(
            isHandlerPeerEntryTransfer(
                entry = catchEntry,
                target = catchEntry,
                source = siblingHandler,
                scopeHandlerEntries = setOf(catchEntry, siblingHandler),
            ),
        )
        assertFalse(
            isHandlerPeerEntryTransfer(
                entry = catchEntry,
                target = catchEntry,
                source = unrelatedHandler,
                scopeHandlerEntries = setOf(catchEntry, siblingHandler),
            ),
        )
        assertFalse(
            isHandlerPeerEntryTransfer(
                entry = catchEntry,
                target = catchInterior,
                source = siblingHandler,
                scopeHandlerEntries = setOf(catchEntry, siblingHandler),
            ),
        )
    }

    // Pseudocode: goto CATCH_BODY from outside; try { ... } catch (E e) { CATCH_BODY } -> reject
    @Test
    fun `rejects unrelated external entry into ordinary catch body`() {
        val entry = BasicBlockId(0)
        val tryBlock = BasicBlockId(1)
        val continuation = BasicBlockId(2)
        val catchEntry = BasicBlockId(3)
        val catchBody = BasicBlockId(4)
        val external = BasicBlockId(5)
        val edges = listOf(
            edge(entry, tryBlock, ControlFlowEdgeKind.CONDITIONAL),
            edge(entry, external, ControlFlowEdgeKind.FALLTHROUGH),
            edge(tryBlock, continuation, ControlFlowEdgeKind.FALLTHROUGH),
            exceptionEdge(tryBlock, catchEntry, "java/io/IOException"),
            edge(catchEntry, catchBody, ControlFlowEdgeKind.FALLTHROUGH),
            edge(catchBody, continuation, ControlFlowEdgeKind.JUMP),
            edge(external, catchBody, ControlFlowEdgeKind.JUMP),
        )
        val graph = exceptionGraph(
            blockCount = 6,
            edges = edges,
            handlers = listOf(exceptionHandler(1, 2, 3, "java/io/IOException")),
        )
        val result = analyzer.analyze(
            graph,
            SsaControlFlowGraph(setOf(entry, tryBlock, continuation, catchEntry, catchBody, external), edges, entry),
            ExpressionAnalysis(emptyMap(), listOf(ExpressionStatement.Return(1, null))),
        )

        assertTrue(result.regions.none { it is StructuredRegion.TryCatch && it.header == tryBlock })
        val diagnostic = result.unstructured.single { it.kind == UnstructuredControlFlowKind.EXCEPTION }
        assertEquals(UnstructuredControlFlowReason.EXCEPTION_HANDLER_HAS_EXTERNAL_ENTRY, diagnostic.reason)
    }

    // Pseudocode: loop: try { ... } catch (E e) { continue loop; }
    @Test
    fun `recognizes catch that continues at protected loop header`() {
        val b0 = BasicBlockId(0)
        val b1 = BasicBlockId(1)
        val b2 = BasicBlockId(2)
        val edges = listOf(
            edge(b0, b1, ControlFlowEdgeKind.FALLTHROUGH),
            exceptionEdge(b0, b2, "java/lang/Exception"),
            edge(b1, b0, ControlFlowEdgeKind.JUMP),
            exceptionEdge(b1, b2, "java/lang/Exception"),
            edge(b2, b0, ControlFlowEdgeKind.JUMP),
        )
        val graph = exceptionGraph(
            blockCount = 3,
            edges = edges,
            handlers = listOf(exceptionHandler(0, 2, 2, "java/lang/Exception")),
        )
        val result = analyzer.analyze(
            graph,
            SsaControlFlowGraph(setOf(b0, b1, b2), edges, b0),
            ExpressionAnalysis(emptyMap(), emptyList()),
        )

        val region = result.regions.filterIsInstance<StructuredRegion.TryCatch>().single()
        assertEquals(b0, region.header)
        assertEquals(setOf(b0, b1), region.tryBlocks)
        assertEquals(setOf(b2), region.catches.single().blocks)
        assertEquals(b0, region.continuation)
        assertEquals(0, result.unstructuredExceptionRegionCount)
    }

    // Pseudocode: try { if (...) return; } catch (E e) { CATCH } AFTER
    @Test
    fun `keeps shared post-catch join outside handler body after terminal try return`() {
        val b0 = BasicBlockId(0)
        val b1 = BasicBlockId(1)
        val b2 = BasicBlockId(2)
        val b3 = BasicBlockId(3)
        val b4 = BasicBlockId(4)
        val b5 = BasicBlockId(5)
        val edges = listOf(
            edge(b0, b5, ControlFlowEdgeKind.CONDITIONAL),
            edge(b0, b1, ControlFlowEdgeKind.FALLTHROUGH),
            edge(b1, b5, ControlFlowEdgeKind.CONDITIONAL),
            edge(b1, b2, ControlFlowEdgeKind.FALLTHROUGH),
            edge(b2, b3, ControlFlowEdgeKind.FALLTHROUGH),
            exceptionEdge(b2, b4, "java/io/IOException"),
            edge(b4, b5, ControlFlowEdgeKind.FALLTHROUGH),
        )
        val graph = exceptionGraph(
            blockCount = 6,
            edges = edges,
            handlers = listOf(exceptionHandler(2, 3, 4, "java/io/IOException")),
        )
        val result = analyzer.analyze(
            graph,
            SsaControlFlowGraph(setOf(b0, b1, b2, b3, b4, b5), edges, b0),
            ExpressionAnalysis(
                emptyMap(),
                listOf(
                    ExpressionStatement.Return(3, null),
                    ExpressionStatement.Return(5, null),
                ),
            ),
        )

        val region = result.regions.filterIsInstance<StructuredRegion.TryCatch>().single()
        assertEquals(setOf(b2, b3), region.tryBlocks)
        assertEquals(setOf(b4), region.catches.single().blocks)
        assertEquals(b5, region.continuation)
        assertEquals(0, result.unstructuredExceptionRegionCount)
    }

    // Pseudocode: try { BODY } catch (E e) { CATCH } BRIDGE; AFTER
    @Test
    fun `uses common source join beyond protected normal bridge`() {
        val b0 = BasicBlockId(0)
        val b1 = BasicBlockId(1)
        val b2 = BasicBlockId(2)
        val b3 = BasicBlockId(3)
        val b4 = BasicBlockId(4)
        val edges = listOf(
            edge(b0, b4, ControlFlowEdgeKind.CONDITIONAL),
            edge(b0, b1, ControlFlowEdgeKind.FALLTHROUGH),
            edge(b1, b2, ControlFlowEdgeKind.FALLTHROUGH),
            exceptionEdge(b1, b3, "java/lang/Exception"),
            edge(b2, b4, ControlFlowEdgeKind.JUMP),
            edge(b3, b4, ControlFlowEdgeKind.FALLTHROUGH),
        )
        val graph = exceptionGraph(
            blockCount = 5,
            edges = edges,
            handlers = listOf(exceptionHandler(1, 2, 3, "java/lang/Exception")),
        )
        val result = analyzer.analyze(
            graph,
            SsaControlFlowGraph(setOf(b0, b1, b2, b3, b4), edges, b0),
            ExpressionAnalysis(emptyMap(), listOf(ExpressionStatement.Return(4, null))),
        )

        val region = result.regions.filterIsInstance<StructuredRegion.TryCatch>().single()
        assertEquals(setOf(b1), region.tryBlocks)
        assertEquals(setOf(b3), region.catches.single().blocks)
        assertEquals(b4, region.continuation)
        assertEquals(0, result.unstructuredExceptionRegionCount)
    }

    // Pseudocode: try { BODY } catch (A | B e) { HANDLER }
    @Test
    fun `groups multi-catch table entries sharing one handler`() {
        val b0 = BasicBlockId(0)
        val b1 = BasicBlockId(1)
        val b2 = BasicBlockId(2)
        val edges = listOf(
            edge(b0, b2, ControlFlowEdgeKind.FALLTHROUGH),
            exceptionEdge(b0, b1, "java/io/IOException"),
            exceptionEdge(b0, b1, "java/lang/RuntimeException"),
            edge(b1, b2, ControlFlowEdgeKind.JUMP),
        )
        val graph = exceptionGraph(
            blockCount = 3,
            edges = edges,
            handlers = listOf(
                exceptionHandler(0, 1, 1, "java/io/IOException"),
                exceptionHandler(0, 1, 1, "java/lang/RuntimeException"),
            ),
        )
        val result = analyzer.analyze(
            graph,
            SsaControlFlowGraph(setOf(b0, b1, b2), edges, b0),
            ExpressionAnalysis(emptyMap(), emptyList()),
        )

        val region = assertIs<StructuredRegion.TryCatch>(result.regions.single())
        assertEquals(1, region.catches.size)
        assertEquals(
            listOf("java/io/IOException", "java/lang/RuntimeException"),
            region.catches.single().catchTypes,
        )
    }

    // Pseudocode: try { BODY } finally { unsupported cleanup shape } -> remain block-based with reason
    @Test
    fun `keeps catch-all exception region block based with explicit diagnostic`() {
        val b0 = BasicBlockId(0)
        val b1 = BasicBlockId(1)
        val edges = listOf(
            exceptionEdge(b0, b1, null),
        )
        val graph = exceptionGraph(
            blockCount = 2,
            edges = edges,
            handlers = listOf(exceptionHandler(0, 1, 1, null)),
        )
        val result = analyzer.analyze(
            graph,
            SsaControlFlowGraph(setOf(b0, b1), edges, b0),
            ExpressionAnalysis(emptyMap(), listOf(ExpressionStatement.Throw(1, ValueId(0)))),
        )

        assertTrue(result.regions.none { it is StructuredRegion.TryCatch })
        val diagnostic = result.unstructured.single { it.kind == UnstructuredControlFlowKind.EXCEPTION }
        assertEquals(UnstructuredControlFlowReason.EXCEPTION_CATCH_ALL_UNSUPPORTED, diagnostic.reason)
        assertEquals(0, diagnostic.protectedStartInstructionIndex)
        assertEquals(1, diagnostic.protectedEndInstructionIndexExclusive)
    }
}
