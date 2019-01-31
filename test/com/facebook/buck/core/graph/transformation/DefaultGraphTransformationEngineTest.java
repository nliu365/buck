/*
 * Copyright 2018-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.core.graph.transformation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import com.facebook.buck.core.graph.transformation.ChildrenAdder.LongNode;
import com.facebook.buck.core.graph.transformation.executor.DepsAwareExecutor;
import com.facebook.buck.core.graph.transformation.executor.DepsAwareTask;
import com.facebook.buck.core.graph.transformation.executor.impl.DefaultDepsAwareExecutor;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.graph.GraphBuilder;
import com.google.common.graph.MutableGraph;
import com.google.common.util.concurrent.Futures;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.LongAdder;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.Timeout;

/** Test and demonstration of {@link DefaultGraphTransformationEngine} */
public class DefaultGraphTransformationEngineTest {

  @Rule public Timeout timeout = Timeout.seconds(10000);
  @Rule public ExpectedException expectedException = ExpectedException.none();

  private MutableGraph<LongNode> graph;
  private TrackingCache cache;
  private DepsAwareExecutor executor;

  @Before
  public void setUp() {
    executor = DefaultDepsAwareExecutor.from(new ForkJoinPool(4));

    graph = GraphBuilder.directed().build();

    /**
     * Make a graph
     *
     * <p>Edges directed down
     *
     * <pre>
     *            1
     *         /  |  \
     *        2  4 <- 5
     *       /
     *      3
     * </pre>
     */
    graph.addNode(ImmutableLongNode.of(1));
    graph.addNode(ImmutableLongNode.of(2));
    graph.addNode(ImmutableLongNode.of(3));
    graph.addNode(ImmutableLongNode.of(4));
    graph.addNode(ImmutableLongNode.of(5));

    graph.putEdge(ImmutableLongNode.of(1), ImmutableLongNode.of(2));
    graph.putEdge(ImmutableLongNode.of(1), ImmutableLongNode.of(4));
    graph.putEdge(ImmutableLongNode.of(1), ImmutableLongNode.of(5));
    graph.putEdge(ImmutableLongNode.of(5), ImmutableLongNode.of(4));
    graph.putEdge(ImmutableLongNode.of(2), ImmutableLongNode.of(3));

    cache = new TrackingCache();
  }

  @After
  public void cleanUp() {
    executor.close();
  }

  /**
   * Demonstration of usage of {@link GraphEngineCache} with stats tracking used to verify behaviour
   * of the {@link DefaultGraphTransformationEngine}.
   */
  private final class TrackingCache implements GraphEngineCache<LongNode, LongNode> {

    private final ConcurrentHashMap<LongNode, LongNode> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<LongNode, LongAdder> hitStats = new ConcurrentHashMap<>();

    @Override
    public Optional<LongNode> get(LongNode k) {
      Optional<LongNode> result = Optional.ofNullable(cache.get(k));
      result.ifPresent(r -> hitStats.get(k).increment());
      return result;
    }

    @Override
    public void put(LongNode k, LongNode v) {
      cache.put(k, v);
      hitStats.put(k, new LongAdder());
    }

    public ImmutableMap<LongNode, LongAdder> getStats() {
      return ImmutableMap.copyOf(hitStats);
    }

    public int getSize() {
      return cache.size();
    }
  }

  @Test
  public void requestOnLeafResultsSameValue() {
    ChildrenAdder transformer = new ChildrenAdder(graph);
    DefaultGraphTransformationEngine<LongNode, LongNode> engine =
        new DefaultGraphTransformationEngine<>(transformer, graph.nodes().size(), executor);
    assertEquals(ImmutableLongNode.of(3), engine.computeUnchecked(ImmutableLongNode.of(3)));

    assertComputationIndexBecomesEmpty(engine.impl.computationIndex);
  }

  @Test
  public void requestOnRootCorrectValue() {
    ChildrenAdder transformer = new ChildrenAdder(graph);
    DefaultGraphTransformationEngine<LongNode, LongNode> engine =
        new DefaultGraphTransformationEngine<>(transformer, graph.nodes().size(), executor);
    assertEquals(ImmutableLongNode.of(19), engine.computeUnchecked(ImmutableLongNode.of(1)));

    assertComputationIndexBecomesEmpty(engine.impl.computationIndex);
  }

  @SuppressWarnings("PMD.EmptyCatchBlock")
  @Test
  public void requestOnRootCorrectValueWithCustomExecutor() {
    ChildrenAdder transformer = new ChildrenAdder(graph);
    DefaultGraphTransformationEngine<LongNode, LongNode> engine =
        new DefaultGraphTransformationEngine<>(transformer, graph.nodes().size(), executor);
    assertEquals(ImmutableLongNode.of(19), engine.computeUnchecked(ImmutableLongNode.of(1)));
    assertComputationIndexBecomesEmpty(engine.impl.computationIndex);

    executor.close();

    DefaultGraphTransformationEngine<LongNode, LongNode> engine2 =
        new DefaultGraphTransformationEngine<>(transformer, graph.nodes().size(), executor);
    try {
      engine2.computeUnchecked(ImmutableLongNode.of(1));
      fail(
          "Did not expect DefaultAsyncTransformationEngine to compute with an executor that has been shut down");
    } catch (RejectedExecutionException e) {
      // this is expected because the custom executor has been shut down
    }
  }

  @Test
  public void canReuseCachedResult() {
    ChildrenAdder transformer = new ChildrenAdder(graph);

    DefaultGraphTransformationEngine<LongNode, LongNode> engine =
        new DefaultGraphTransformationEngine<>(transformer, graph.nodes().size(), cache, executor);
    LongNode result = engine.computeUnchecked(ImmutableLongNode.of(3));

    assertEquals(ImmutableLongNode.of(3), result);

    transformer =
        new ChildrenAdder(graph) {
          @Override
          public LongNode transform(LongNode node, TransformationEnvironment env) {
            fail("Did not expect call as cache should be used");
            return super.transform(node, env);
          }
        };

    engine =
        new DefaultGraphTransformationEngine<>(transformer, graph.nodes().size(), cache, executor);
    LongNode newResult = engine.computeUnchecked(ImmutableLongNode.of(3));

    assertEquals(result, newResult);

    // all Futures should be removed
    assertComputationIndexBecomesEmpty(engine.impl.computationIndex);
    assertEquals(1, cache.getSize());
    assertEquals(1, cache.hitStats.get(ImmutableLongNode.of(3)).intValue());
  }

  @Test
  public void canReusePartiallyCachedResult() {
    ChildrenAdder transformer = new ChildrenAdder(graph);
    DefaultGraphTransformationEngine<LongNode, LongNode> engine =
        new DefaultGraphTransformationEngine<>(transformer, graph.nodes().size(), cache, executor);

    assertEquals(ImmutableLongNode.of(9), engine.computeUnchecked(ImmutableLongNode.of(5)));
    assertEquals(ImmutableLongNode.of(3), engine.computeUnchecked(ImmutableLongNode.of(3)));

    /**
     *
     *
     * <pre>
     *            1
     *         /  |  \
     *        2  4 <- 5
     *       /
     *      3
     * </pre>
     *
     * <p>So we now have 5, 4, 3 in the cache to be reused.
     */
    transformer =
        new ChildrenAdder(graph) {
          @Override
          public LongNode transform(LongNode node, TransformationEnvironment env) {
            if (node.get() == 5L || node.get() == 4L || node.get() == 3L) {
              fail("Did not expect call as cache should be used");
            }
            return super.transform(node, env);
          }
        };
    engine =
        new DefaultGraphTransformationEngine<>(transformer, graph.nodes().size(), cache, executor);

    // reuse the cache
    assertEquals(ImmutableLongNode.of(19), engine.computeUnchecked(ImmutableLongNode.of(1)));

    // all Futures should be removed
    assertComputationIndexBecomesEmpty(engine.impl.computationIndex);
    assertEquals(5, cache.getSize());
    assertEquals(0, cache.hitStats.get(ImmutableLongNode.of(1)).intValue());
    assertEquals(0, cache.hitStats.get(ImmutableLongNode.of(2)).intValue());
    assertEquals(1, cache.hitStats.get(ImmutableLongNode.of(5)).intValue());
    assertEquals(1, cache.hitStats.get(ImmutableLongNode.of(3)).intValue());
    assertEquals(1, cache.hitStats.get(ImmutableLongNode.of(4)).intValue());
  }

  @Test
  public void handlesTransformerThatThrowsInTransform()
      throws ExecutionException, InterruptedException {

    Exception exception = new Exception();
    expectedException.expectCause(Matchers.sameInstance(exception));

    GraphTransformer<LongNode, LongNode> transformer =
        new GraphTransformer<LongNode, LongNode>() {

          @Override
          public Class<LongNode> getKeyClass() {
            return LongNode.class;
          }

          @Override
          public LongNode transform(LongNode aLong, TransformationEnvironment env)
              throws Exception {
            throw exception;
          }

          @Override
          public ImmutableSet<LongNode> discoverDeps(LongNode key, TransformationEnvironment env) {
            return ImmutableSet.of();
          }

          @Override
          public ImmutableSet<LongNode> discoverPreliminaryDeps(LongNode aLong) {
            return ImmutableSet.of();
          }
        };

    DefaultGraphTransformationEngine<LongNode, LongNode> engine =
        new DefaultGraphTransformationEngine<>(transformer, graph.nodes().size(), cache, executor);

    engine.compute(ImmutableLongNode.of(1)).get();
  }

  @Test
  public void handlesTransformerThatThrowsInDiscoverPreliminaryDeps()
      throws ExecutionException, InterruptedException {

    Exception exception = new Exception();
    expectedException.expectCause(Matchers.sameInstance(exception));

    GraphTransformer<LongNode, LongNode> transformer =
        new GraphTransformer<LongNode, LongNode>() {

          @Override
          public Class<LongNode> getKeyClass() {
            return LongNode.class;
          }

          @Override
          public LongNode transform(LongNode aLong, TransformationEnvironment env) {
            return ImmutableLongNode.of(1);
          }

          @Override
          public ImmutableSet<LongNode> discoverDeps(LongNode key, TransformationEnvironment env) {
            fail("Should not have gotten to discoverDeps since preliminary deps discovery failed");
            return ImmutableSet.of();
          }

          @Override
          public ImmutableSet<LongNode> discoverPreliminaryDeps(LongNode aLong) throws Exception {
            throw exception;
          }
        };

    DefaultGraphTransformationEngine<LongNode, LongNode> engine =
        new DefaultGraphTransformationEngine<>(transformer, graph.nodes().size(), cache, executor);

    engine.compute(ImmutableLongNode.of(1)).get();
  }

  @Test
  public void handlesTransformerThatThrowsInDiscoverDeps()
      throws ExecutionException, InterruptedException {

    Exception exception = new Exception();
    expectedException.expectCause(Matchers.sameInstance(exception));

    GraphTransformer<LongNode, LongNode> transformer =
        new GraphTransformer<LongNode, LongNode>() {

          @Override
          public Class<LongNode> getKeyClass() {
            return LongNode.class;
          }

          @Override
          public LongNode transform(LongNode aLong, TransformationEnvironment env) {
            return ImmutableLongNode.of(1);
          }

          @Override
          public ImmutableSet<LongNode> discoverDeps(LongNode key, TransformationEnvironment env)
              throws Exception {
            throw exception;
          }

          @Override
          public ImmutableSet<LongNode> discoverPreliminaryDeps(LongNode aLong) throws Exception {
            return ImmutableSet.of();
          }
        };

    DefaultGraphTransformationEngine<LongNode, LongNode> engine =
        new DefaultGraphTransformationEngine<>(transformer, graph.nodes().size(), cache, executor);

    engine.compute(ImmutableLongNode.of(1)).get();
  }

  @Test
  public void requestOnRootWithTwoStageDepsCorrectValue() {
    ChildrenAdder transformer =
        new ChildrenAdder(graph) {
          @Override
          public ImmutableSet<LongNode> discoverDeps(LongNode key, TransformationEnvironment env) {
            return ImmutableSet.copyOf(
                Sets.difference(super.discoverPreliminaryDeps(key), env.getDeps().keySet()));
          }

          @Override
          public ImmutableSet<LongNode> discoverPreliminaryDeps(LongNode key) {
            return ImmutableSet.copyOf(
                Sets.filter(super.discoverPreliminaryDeps(key), node -> node.get() % 2 == 0));
          }
        };
    DefaultGraphTransformationEngine<LongNode, LongNode> engine =
        new DefaultGraphTransformationEngine<>(transformer, graph.nodes().size(), executor);
    assertEquals(ImmutableLongNode.of(19), engine.computeUnchecked(ImmutableLongNode.of(1)));

    assertComputationIndexBecomesEmpty(engine.impl.computationIndex);
  }

  /**
   * Asserts that the computationIndex of the {@link GraphTransformationEngine} eventually becomes
   * empty.
   *
   * @param computationIndex the computationIndex of the engine
   */
  private static void assertComputationIndexBecomesEmpty(
      ConcurrentHashMap<ComputeKey<? extends ComputeResult>, ? extends DepsAwareTask<?, ?>>
          computationIndex) {
    // wait for all tasks to complete in the computation.
    // we can have situation where the computation was completed by using the cache.
    for (DepsAwareTask<?, ?> task : computationIndex.values()) {
      Futures.getUnchecked(task.getResultFuture());
    }

    assertEquals(0, computationIndex.size());
  }
}
