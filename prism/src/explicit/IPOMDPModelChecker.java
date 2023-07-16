//==============================================================================
//
//	Copyright (c) 2023-
//	Authors:
//	* Dave Parker <david.parker@cs.ox.ac.uk> (University of Oxford)
//
//------------------------------------------------------------------------------
//
//	This file is part of PRISM.
//
//	PRISM is free software; you can redistribute it and/or modify
//	it under the terms of the GNU General Public License as published by
//	the Free Software Foundation; either version 2 of the License, or
//	(at your option) any later version.
//
//	PRISM is distributed in the hope that it will be useful,
//	but WITHOUT ANY WARRANTY; without even the implied warranty of
//	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
//	GNU General Public License for more details.
//
//	You should have received a copy of the GNU General Public License
//	along with PRISM; if not, write to the Free Software Foundation,
//	Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
//
//==============================================================================

package explicit;

import java.util.*;

import acceptance.AcceptanceReach;
import common.IntSet;
import common.Interval;
import common.IterableStateSet;
import explicit.rewards.MDPRewards;
import explicit.rewards.Rewards;
import org.apache.logging.log4j.core.util.ArrayUtils;
import org.junit.jupiter.api.DisplayNameGenerator;
import parser.State;
import parser.ast.Expression;
import prism.*;
import strat.FMDStrategyStep;
import strat.FMDStrategyProduct;
import strat.MDStrategy;
import strat.MDStrategyArray;
import strat.Strategy;
import gurobi.*;

/**
 * Explicit-state model checker for interval Markov decision prcoesses (IMDPs).
 */
public class IPOMDPModelChecker extends ProbModelChecker
{
	private class Parameters {
		public final double penaltyWeight;
		public final double trustRegion;
		public final double regionChangeFactor;
		public final double regionThreshold;

		public Parameters(double penaltyWeight, double trustRegion, double regionChangeFactor, double regionThreshold) {
			this.penaltyWeight = penaltyWeight;
			this.trustRegion = trustRegion;
			this.regionChangeFactor = regionChangeFactor;
			this.regionThreshold = regionThreshold;
		}
	}

	private class SimpleIPOMDP {
		public final ArrayList<Integer> uncertainStates;
		public final ArrayList<Integer> actionStates;
		public final Distribution[] transitions;
		public final int[] observations;

		public SimpleIPOMDP(ArrayList<Integer> uncertainStates, ArrayList<Integer> actionStates, Distribution[] transitions, int[] observations) {
			this.uncertainStates = uncertainStates;
			this.actionStates = actionStates;
			this.transitions = transitions;
			this.observations = observations;
		}

		public Integer getNumStates() {
			return uncertainStates.size() + actionStates.size();
		}
	}

	private class MainVariables {
		public final double[] policy;
		public final double[] prob;

		public MainVariables(double[] policy, double[] prob) {
			this.policy = policy;
			this.prob = prob;
		}
	}

	private class Edge <X, Y> {
		public final X state;
		public final Y interval;
		public Edge(X state, Y interval) {
			this.state = state;
			this.interval= interval;
		}
	}

	/**
	 * Helper class for defining the transitions.
	 * This data type will denote one of the two:
	 * 1) Several pairs of form (state, interval) denoting the distribution of an uncertain state
	 * 2) Several pairs of form (state, [-1, 1]) denoting the choices in an action state
	 */
	private class Distribution extends ArrayList<Edge<Integer, Interval<Double>>> {
	}

	/**
	 * Create a new IPOMDPModelChecker, inherit basic state from parent (unless null).
	 */
	public IPOMDPModelChecker(PrismComponent parent) throws PrismException
	{
		super(parent);
	}

	/**
	 * Compute until probabilities.
	 * i.e. compute the probability of reaching a state in {@code target}.
	 * @param ipomdp The IPOMDP
	 * @param remain Remain in these states (optional: null means "all")
	 * @param target Target states
	 * @param minMax Min/max info
	 */
	public ModelCheckerResult computeUntilProbs(IPOMDP<Double> ipomdp, BitSet remain, BitSet target, MinMax minMax) throws PrismException
	{
		return computeReachProbs(ipomdp, remain, target, minMax);
	}

	/**
	 * Compute reachability/until probabilities.
	 * i.e. compute the probability of reaching a state in {@code target},
	 * while remaining in those in {@code remain}.
	 * @param ipomdp The IPOMDP
	 * @param remain Remain in these states (optional: null means "all")
	 * @param target Target states
	 * @param minMax Min/max info
	 */
	public ModelCheckerResult computeReachProbs(IPOMDP<Double> ipomdp, BitSet remain, BitSet target, MinMax minMax) throws PrismException
	{
		// Construct the binary/simple version of the IPOMDP
		List<Object> wrappedSimpleIPOMDP = determineSupport(ipomdp);
		SimpleIPOMDP simpleIPOMDP = new SimpleIPOMDP((ArrayList<Integer>) wrappedSimpleIPOMDP.get(0), (ArrayList<Integer>) wrappedSimpleIPOMDP.get(1), (Distribution[]) wrappedSimpleIPOMDP.get(2), (int[]) wrappedSimpleIPOMDP.get(3));

		// Determine goal states in the simple IPOMDP
		ArrayList<Integer> goalStates = determineGoalStates(target, (int[]) wrappedSimpleIPOMDP.get(4));

		// Initialise parameters
		Parameters parameters = new Parameters(1e4, 1.5, 1.5, 1e-4);

		// Initialise policy and therefore reaching probability vector
		MainVariables mainVariables = initialiseMainVariables(simpleIPOMDP);

		// Induced IDTMC
		returnInducedIDTMC(simpleIPOMDP, mainVariables.policy);

		// Solve the Linear Programming
		try {
			solveLinearProgrammingWithGurobi(goalStates, mainVariables, parameters, simpleIPOMDP);
		} catch (GRBException e) {
			throw new PrismException("Error solving LP: " +  e.getMessage());
		}

		// Return dummy result vector
		ModelCheckerResult res = new ModelCheckerResult();
		int total = ipomdp.getNumStates();
		res.soln = new double[total];
		return res;
	}

	public IDTMCSimple<Double> returnInducedIDTMC(SimpleIPOMDP simpleIPOMDP, double[] policy) throws PrismException
	{
		// Create explicit IDTMC
		IDTMCSimple<Double> idtmc = new IDTMCSimple<>(simpleIPOMDP.getNumStates());

		// Add transitions from uncertain states
		for (int state : simpleIPOMDP.uncertainStates)
			for (Edge<Integer, Interval<Double>> transition : simpleIPOMDP.transitions[state]) {
				int successor = transition.state;
				Interval<Double> interval = transition.interval;

				idtmc.setProbability(state, successor, interval);
			}

		// Add transitions from action states
		for (int state : simpleIPOMDP.actionStates)
			for (int k = 0; k <= 1; k++) {
				int successor = simpleIPOMDP.transitions[state].get(k).state;
				Interval<Double> interval = new Interval<>(policy[2 * state + k], policy[2 * state + k]);

				idtmc.setProbability(state, successor, interval);
			}

		return idtmc;
	}

	public ArrayList<Integer> determineGoalStates(BitSet target, int[] gadget)
	{
		ArrayList<Integer> goalStates = new ArrayList<>();
		int indexOfGoalState = target.nextSetBit(0);

		while (indexOfGoalState >= 0) {
			goalStates.add(gadget[indexOfGoalState]);
			indexOfGoalState = target.nextSetBit(indexOfGoalState + 1);
		}

		return goalStates;
	}

	public MainVariables initialiseMainVariables(SimpleIPOMDP simpleIPOMDP) {
		int numStates = simpleIPOMDP.getNumStates();

		double[] policy = new double[2 * numStates];
		double[] prob = new double[numStates];

		for (int s : simpleIPOMDP.uncertainStates) {
			policy[2 * s] = 1.0;
		}

		for (int s : simpleIPOMDP.actionStates) {
			policy[2 * s] = 0.5;
			policy[2 * s + 1] = 0.5;
		}

		// TODO
		// compute prob
		Arrays.fill(prob, 1);

		return new MainVariables(policy, prob);
	}

	public void solveLinearProgrammingWithGurobi(ArrayList<Integer> goalStates, MainVariables mainVariables, Parameters parameters, SimpleIPOMDP simpleIPOMDP) throws GRBException
	{
		// Number of states in the simple IPOMDP
		int n = simpleIPOMDP.getNumStates();

		// Set up the environment for Gurobi
		GRBEnv env = new GRBEnv("gurobi.log");
		GRBModel model = new GRBModel(env);

		// Create the variables for the probabilities
		GRBVar probVars[] = new GRBVar[n];
		for (int s = 0; s < n; s++) {
			probVars[s] = model.addVar(0.0, 1.0, 0.0, GRB.CONTINUOUS, "p" + s);
		}

		// Create the variables for the policy
		GRBVar policyVars[] = new GRBVar[2 * n];

		// Handle the policy variables for the uncertain states
		// There will be exactly one such variable for each state
		for (int s : simpleIPOMDP.uncertainStates) {
			policyVars[2 * s] = model.addVar(0.0, 1.0, 0.0, GRB.CONTINUOUS, "policy" + s + "a");
		}

		// Handle the policy variables for the action states
		// There will be exactly two such variables for each state
		for (int s : simpleIPOMDP.actionStates) {
			policyVars[2 * s] = model.addVar(0.0, 1.0, 0.0, GRB.CONTINUOUS, "policy" + s + "a");
			policyVars[2 * s + 1] = model.addVar(0.0, 1.0, 0.0, GRB.CONTINUOUS, "policy" + s + "b");
		}

		policyMustBeObservationBased(model, policyVars, simpleIPOMDP);
		policyMustPreserveUnderlyingGraph(model, policyVars, simpleIPOMDP);
		policyMustBeValidDistribution(model, policyVars, simpleIPOMDP);

		addConstraintsForUncertainStates(model, probVars, simpleIPOMDP);

		addConstraintsForTrustRegions(model, policyVars, probVars, mainVariables, parameters, simpleIPOMDP);

		addConstraintsForGoalStates(model, probVars, goalStates);

		// Optimise the model
		model.optimize();

		// Dispose of model and environment
		model.dispose();
		env.dispose();
	}

	public void addConstraintsForGoalStates(GRBModel model, GRBVar probVars[], ArrayList<Integer> goalStates) throws GRBException
	{
		for (int goalState : goalStates) {
			GRBLinExpr constraint = new GRBLinExpr();
			constraint.addTerm(1.0, probVars[goalState]);
			model.addConstr(constraint, GRB.EQUAL, 1.0, "goalState" + goalState);
		}
	}

	public void addConstraintsForTrustRegions(GRBModel model, GRBVar policyVars[], GRBVar probVars[], MainVariables mainVariables, Parameters parameters, SimpleIPOMDP simpleIPOMDP) throws GRBException
	{
		int numStates = simpleIPOMDP.getNumStates();
		double[] prob = mainVariables.prob;
		double[] policy = mainVariables.policy;
		double trustRegion = parameters.trustRegion + 1;

		for (int s = 0; s < numStates; s++) {
			GRBLinExpr firstConstraint = new GRBLinExpr();
			firstConstraint.addTerm(1.0, probVars[s]);
			model.addConstr(firstConstraint, GRB.GREATER_EQUAL, prob[s] / trustRegion, "trustRegionProbLeft" + s);

			GRBLinExpr secondConstraint = new GRBLinExpr();
			secondConstraint.addTerm(1.0, probVars[s]);
			model.addConstr(secondConstraint, GRB.LESS_EQUAL, prob[s] * trustRegion, "trustRegionProbRight" + s);
		}

		for (int s : simpleIPOMDP.actionStates)
			for (int k = 0; k <= 1; k++) {
				GRBLinExpr firstConstraint = new GRBLinExpr();
				firstConstraint.addTerm(1.0, policyVars[2 * s + k]);
				model.addConstr(firstConstraint, GRB.GREATER_EQUAL, policy[2 * s + k] / trustRegion, "trustRegionPolicyLeft" + s);

				GRBLinExpr secondConstraint = new GRBLinExpr();
				secondConstraint.addTerm(1.0, policyVars[2 * s + k]);
				model.addConstr(secondConstraint, GRB.LESS_EQUAL, policy[2 * s + k] * trustRegion, "trustRegionPolicyRight" + s);
			}
	}

	public void addConstraintsForUncertainStates(GRBModel model, GRBVar probVars[], SimpleIPOMDP simpleIPOMDP) throws GRBException
	{
		// We have the constraints a(i) <= u(i) <= b(i) and sum_i u(i) = 1
		// We encode these into the equation C * u + g >= 0
		for (int s : simpleIPOMDP.uncertainStates) {
			int n = simpleIPOMDP.transitions[s].size();
			int m = 2 * n + 2;

			// Keep track of the successor states
			ArrayList<Integer> successors = new ArrayList<>();

			// Create vector g
			double[] g = new double[m];

			// Populate successors and determine vector g
			for (int i = 0; i < n; i++) {
				Edge<Integer, Interval<Double>> transition = simpleIPOMDP.transitions[s].get(i);

				successors.add(transition.state);
				g[2 * i] = -transition.interval.getLower();
				g[2 * i + 1] = transition.interval.getUpper();
			}
			g[2 * n] = -1.0;
			g[2 * n + 1] = 1.0;

			// Create the dual variables
			GRBVar dualVars[] = new GRBVar[m];
			for (int i = 0; i < m; i++) {
				dualVars[i] = model.addVar(0.0, 1e9, 0.0, GRB.CONTINUOUS, "dual" + s + "," + i);
			}

			// Introduce the probability inequality
			GRBLinExpr inequality = new GRBLinExpr();
			inequality.addTerm(-1.0, probVars[s]);
			for (int i = 0; i < m; i++) {
				inequality.addTerm(g[i], dualVars[i]);
			}
			model.addConstr(inequality, GRB.GREATER_EQUAL, 0.0, "dualizationInequality" + s);

			// Introduce the dualization constraints
			for (int i = 0; i < n; i++) {
				int successor = successors.get(i);

				GRBLinExpr constraint = new GRBLinExpr();
				constraint.addTerm(1.0, probVars[successor]);

				constraint.addTerm(1.0, dualVars[2 * i]);
				constraint.addTerm(-1.0, dualVars[2 * i + 1]);

				constraint.addTerm(1.0, dualVars[2 * n]);
				constraint.addTerm(-1.0, dualVars[2 * n + 1]);

				model.addConstr(constraint, GRB.EQUAL, 0.0, "dualizationConstraint" + s + "," + i);
			}
		}
	}

	public void policyMustPreserveUnderlyingGraph(GRBModel model, GRBVar policyVars[], SimpleIPOMDP simpleIPOMDP) throws GRBException
	{
		double smallValue = 1e-9;

		for (int s : simpleIPOMDP.uncertainStates) {
			GRBLinExpr underlyingGraph = new GRBLinExpr();
			underlyingGraph.addTerm(1.0, policyVars[2 * s]);
			model.addConstr(underlyingGraph, GRB.GREATER_EQUAL, smallValue, "underlyingGraph" + s);
		}

		for (int s : simpleIPOMDP.actionStates)
			for (int k = 0; k <= 1; k++) {
				GRBLinExpr underlyingGraph = new GRBLinExpr();
				underlyingGraph.addTerm(1.0, policyVars[2 * s + k]);
				model.addConstr(underlyingGraph, GRB.GREATER_EQUAL, smallValue, "underlyingGraph" + (s + k));
			}
	}

	public void policyMustBeValidDistribution(GRBModel model, GRBVar policyVars[], SimpleIPOMDP simpleIPOMDP) throws GRBException
	{
		for (int s : simpleIPOMDP.uncertainStates) {
			GRBLinExpr validDistribution = new GRBLinExpr();
			validDistribution.addTerm(1.0, policyVars[2 * s]);
			model.addConstr(validDistribution, GRB.EQUAL, 1.0, "policyUncertainState" + s);
		}

		for (int s : simpleIPOMDP.actionStates) {
			GRBLinExpr validDistribution = new GRBLinExpr();
			validDistribution.addTerm(1.0, policyVars[2 * s]);
			validDistribution.addTerm(1.0, policyVars[2 * s + 1]);
			model.addConstr(validDistribution, GRB.EQUAL, 1.0, "policyActionState" + s);
		}
	}

	public void policyMustBeObservationBased(GRBModel model, GRBVar policyVars[], SimpleIPOMDP simpleIPOMDP) throws GRBException
	{
		int n = simpleIPOMDP.getNumStates();
		int[] leaderOfObservation = new int[n];
		for (int s = n - 1; s >= 0; s--)
			leaderOfObservation[ simpleIPOMDP.observations[s] ] = s;

		for (int s : simpleIPOMDP.uncertainStates) {
			int idx = leaderOfObservation[ simpleIPOMDP.observations[s] ];
			GRBLinExpr observationBased = new GRBLinExpr();
			observationBased.addTerm(1.0, policyVars[2 * s]);
			model.addConstr(observationBased, GRB.EQUAL, policyVars[2 * idx], "policyObservation" + s);
		}

		for (int s : simpleIPOMDP.actionStates)
			for (int k = 0; k <= 1; k++) {
				int idx = leaderOfObservation[ simpleIPOMDP.observations[s] ];
				GRBLinExpr observationBased = new GRBLinExpr();
				observationBased.addTerm(1.0, policyVars[2 * s + k]);
				model.addConstr(observationBased, GRB.EQUAL, policyVars[2 * idx + k], "policyObservation" + s);
			}
	}

	/**
	 * Determine the support graph of the binary/simple IPOMDP (i.e. states, transitions).
	 * @param ipomdp The IPOMDP which must be transformed
	 */
	public List<Object> determineSupport(IPOMDP<Double> ipomdp)
	{
		int numStates = ipomdp.getNumStates();

		// Find the exact number of states of the binary/simple variant
		int numStatesAfterProcess = 0;
		for (int s = 0; s < ipomdp.getNumStates(); s++)
			numStatesAfterProcess = numStatesAfterProcess + 2 * ipomdp.getNumChoices(s) - 1;

		// Function which maps each state of the initial IPOMDP to the "gadget" in the binary/simple variant
		int gadget[] = new int[numStates];
		Arrays.fill(gadget, -1);

		// List of states showing the order in which states were created in the binary/simple variant
		ArrayList<Integer> traversal = new ArrayList<>();

		// Representation of the support graph
		ArrayList<Integer> uncertainStates = new ArrayList<Integer>();
		ArrayList<Integer> actionStates = new ArrayList<Integer>();
		Distribution[] transitions = new Distribution[numStatesAfterProcess];

		int lastStateAdded = -1;
		for (int state = 0; state < ipomdp.getNumStates(); state++) {
			if (gadget[state] < 0) gadget[state] = ++lastStateAdded;

			int numChoices = ipomdp.getNumChoices(state);
			for (int dummy = 0; dummy < numChoices - 1; dummy++) {
				int currState = (dummy == 0 ? gadget[state]: ++lastStateAdded);
				traversal.add(currState);

				actionStates.add(currState);
				Distribution distribution = new Distribution();
				distribution.add(new Edge(lastStateAdded + 1, new Interval<>(-1.0, 1.0)));
				distribution.add(new Edge(lastStateAdded + numChoices, new Interval<>(-1.0, 1.0)));
				transitions[currState] = distribution;
			}

			int lastStateAddedFuture = (numChoices == 1 ? lastStateAdded : lastStateAdded + numChoices);
			for (int choice = 0; choice < numChoices; choice++) {
				int currState = (numChoices == 1 ? gadget[state] : ++lastStateAdded);
				traversal.add(currState);

				uncertainStates.add(currState);
				Distribution distribution = new Distribution();
				Iterator<Map.Entry<Integer, Interval<Double>>> iterator = ipomdp.getTransitionsIterator(state, choice);
				while (iterator.hasNext()) {
					Map.Entry<Integer, Interval<Double>> elem = iterator.next();
					int successor = elem.getKey();
					Interval<Double> interval = elem.getValue();

					if (gadget[successor] < 0) gadget[successor] = ++lastStateAddedFuture;
					distribution.add(new Edge(gadget[successor], interval));
				}

				transitions[currState] = distribution;
			}

			lastStateAdded = lastStateAddedFuture;
		}

		// Determine observations of the simple IPOMDP
		int[] observations = determineObservations(ipomdp, traversal, gadget);

		return Arrays.asList(uncertainStates, actionStates, transitions, observations, gadget);
	}

	/**
	 * Determine the observations of the binary/simple IPOMDP.
	 * @param ipomdp The IPOMDP which must be transformed
	 * @param traversal The order in which the states were created in the simple IPOMDP
	 * @param gadget The mapping of each state from the initial IPOMDP to its correspondent in the simple variant
	 */
	public int[] determineObservations(IPOMDP<Double> ipomdp, ArrayList<Integer> traversal, int[] gadget)
	{
		int numStates = ipomdp.getNumStates();
		int numStatesAfterProcess = traversal.size();

		// Inverse (partial) function of the gadget function
		int[] gadget_inv = new int[numStatesAfterProcess];
		Arrays.fill(gadget_inv, -1);
		for (int s = 0; s < numStates; s++)
			gadget_inv[ gadget[s] ] = s;

		// Function which maps each observation of the initial IPOMDP to some observation in the new one
		int[] freshObservations = new int[numStates];
		Arrays.fill(freshObservations, -1);

		// Array of observations for the states in the binary/simple variant
		int[] observations = new int[numStatesAfterProcess];

		int lastObservationAdded = -1;
		int indexObservation = -1;
		for (int i = 0; i < traversal.size(); i++) {
			int state = traversal.get(i);

			// If the current state in the binary/simple variant is not the head of any gadget
			if (gadget_inv[state] < 0) {
				lastObservationAdded = Math.max(lastObservationAdded, indexObservation);
				observations[state] = indexObservation++;
				continue;
			}

			int initialState = gadget_inv[state];
			int initialObservation = ipomdp.getObservation(initialState);
			if (freshObservations[initialObservation] < 0)
				freshObservations[initialObservation] = ++lastObservationAdded;

			indexObservation = freshObservations[initialObservation];
			observations[state] = indexObservation++;
		}

		return observations;
	}
}
