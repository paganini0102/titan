package com.thinkaurelius.titan.graphdb.tinkerpop.optimize;

import com.thinkaurelius.titan.core.TitanVertex;
import com.thinkaurelius.titan.graphdb.types.system.ImplicitKey;
import org.apache.tinkerpop.gremlin.process.traversal.*;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.HasStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.IsStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.TraversalFilterStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.EdgeOtherVertexStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.EdgeVertexStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.VertexStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.sideEffect.GraphStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.sideEffect.IdentityStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.HasContainer;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.AbstractTraversalStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.util.TraversalHelper;
import org.apache.tinkerpop.gremlin.structure.Direction;
import org.apache.tinkerpop.gremlin.structure.Vertex;

import java.util.List;

/**
 * @author Matthias Broecheler (me@matthiasb.com)
 */
public class AdjacentVertexFilterOptimizerStrategy extends AbstractTraversalStrategy<TraversalStrategy.VendorOptimizationStrategy> implements TraversalStrategy.VendorOptimizationStrategy {

    private static final AdjacentVertexFilterOptimizerStrategy INSTANCE = new AdjacentVertexFilterOptimizerStrategy();

    private AdjacentVertexFilterOptimizerStrategy() {
    }

    public static AdjacentVertexFilterOptimizerStrategy instance() {
        return INSTANCE;
    }


    @Override
    public void apply(final Traversal.Admin<?, ?> traversal) {

        TraversalHelper.getStepsOfClass(TraversalFilterStep.class, traversal).forEach(originalStep -> {
            // Check if this filter traversal matches the pattern: _.inV/outV/otherV.is(x)
            Traversal.Admin<?, ?> filterTraversal = (Traversal.Admin<?, ?>) originalStep.getLocalChildren().get(0);
            List<Step> steps = filterTraversal.getSteps();
            if (steps.size()==2 &&
                    (steps.get(0) instanceof EdgeVertexStep || steps.get(0) instanceof EdgeOtherVertexStep) &&
                    (steps.get(1) instanceof IsStep)) {
                //Get the direction in which we filter on the adjacent vertex (or null if not a valid adjacency filter)
                Direction direction = null;
                if (steps.get(0) instanceof EdgeVertexStep) {
                    EdgeVertexStep evs = (EdgeVertexStep)steps.get(0);
                    if (evs.getDirection()!=Direction.BOTH) direction = evs.getDirection();
                } else {
                    assert steps.get(0) instanceof EdgeOtherVertexStep;
                    direction = Direction.BOTH;
                }
                P predicate = ((IsStep)steps.get(1)).getPredicate();
                //Check that we have a valid direction and a valid vertex filter predicate
                if (direction!=null && predicate.getBiPredicate()== Compare.eq && predicate.getValue() instanceof Vertex) {
                    TitanVertex vertex = TitanTraversalUtil.getTitanVertex((Vertex)predicate.getValue());

                    //Now, check that this step is preceeded by VertexStep that returns edges
                    Step<?, ?> currentStep = originalStep.getPreviousStep();
                    while (true) {
                        if (currentStep instanceof HasStep || currentStep instanceof IdentityStep) {
                            //We can jump over those steps as we move backward
                        } else break;
                    }
                    if (currentStep instanceof VertexStep) {
                        VertexStep vstep = (VertexStep)currentStep;
                        if (vstep.returnsEdge()
                                && (direction==Direction.BOTH || direction.equals(vstep.getDirection().opposite()))) {
                            //Now replace the step with a has condition
                            TraversalHelper.replaceStep(originalStep,
                                    new HasStep(traversal,
                                            HasContainer.makeHasContainers(ImplicitKey.ADJACENT_ID.name(), P.eq(vertex))),
                                    traversal);
                        }
                    }

                }
            }

        });

    }
}
