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

import java.lang.reflect.Array;
import java.util.Collections;
import java.util.*;

import acceptance.AcceptanceReach;
import common.IntSet;
import common.Interval;
import common.IterableStateSet;
import explicit.rewards.*;
import org.apache.logging.log4j.core.util.ArrayUtils;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.platform.console.shadow.picocli.CommandLine;
import parser.State;
import parser.ast.Expression;
import prism.*;
import strat.FMDStrategyStep;
import strat.FMDStrategyProduct;
import strat.MDStrategy;
import strat.MDStrategyArray;
import strat.Strategy;
import gurobi.*;

import java.lang.Math;

/**
 * Explicit-state model checker for interval Markov decision prcoesses (IMDPs).
 */
public class IPOMDPModelChecker extends ProbModelChecker {
	private static GRBEnv env;

	private class Edge {
		public final int state;
		public final Interval<Double> interval;

		public Edge(int state, Interval<Double> interval) {
			this.state = state;
			this.interval = interval;
		}
	}

	private class SimpleDistribution extends ArrayList<Edge> {
	}

	private class ProductBetweenIPOMDPAndFSC {
		IPOMDPSimple<Double> ipomdp;
		MDPRewardsSimple<Double> rewards;
		BitSet remain;
		BitSet target;
		int[] observationList;
		int initialState;

		public ProductBetweenIPOMDPAndFSC(IPOMDP<Double> initIPOMDP, MDPRewards<Double> initMDPRewards, BitSet initRemain, BitSet initTarget, int memoryStatesFSC) {
			// The number of states for each of the models
			int numStatesFSC = memoryStatesFSC;
			int numStatesIPOMDP = initIPOMDP.getNumStates();
			int numStatesProduct = numStatesIPOMDP * numStatesFSC;

			// Create the product arguments
			this.initialState = initIPOMDP.getFirstInitialState() * numStatesFSC;
			this.ipomdp = new IPOMDPSimple<>(numStatesProduct);
			this.rewards = new MDPRewardsSimple<>(numStatesProduct);
			this.remain = new BitSet(numStatesProduct);
			this.target = new BitSet(numStatesProduct);
			this.observationList = new int[numStatesProduct];

			// Check if the reward structure is not presented
			// That means that the model checker requires pure reaching probabilities
			if (initMDPRewards == null)
				rewards = null;

			for (int stateIPOMDP = 0; stateIPOMDP < numStatesIPOMDP; stateIPOMDP++) {
				for (int stateFSC = 0; stateFSC < numStatesFSC; stateFSC++) {
					int productState = (stateIPOMDP * numStatesFSC) + stateFSC;

					// Construct the product 'remain' and 'target'
					if (initRemain.get(stateIPOMDP)) remain.set(productState);
					if (initTarget.get(stateIPOMDP)) target.set(productState);

					// Construct the state reward
					if (initMDPRewards != null)
						rewards.setStateReward(productState, initMDPRewards.getStateReward(stateIPOMDP));

					// Add transitions to other states of the product
					for (int actionIPOMDP = 0; actionIPOMDP < initIPOMDP.getNumChoices(stateIPOMDP); actionIPOMDP++) {
						for (int actionFSC = 0; actionFSC < numStatesFSC; actionFSC++) {
							Distribution<Interval<Double>> distribution = new Distribution<>(Evaluator.forDoubleInterval());

							Iterator<Map.Entry<Integer, Interval<Double>>> iterator = initIPOMDP.getTransitionsIterator(stateIPOMDP, actionIPOMDP);
							while (iterator.hasNext()) {
								Map.Entry<Integer, Interval<Double>> elem = iterator.next();
								int successor = elem.getKey();
								Interval<Double> interval = elem.getValue();

								// Add transition to the successor state
								// Note that action 'actionFSC' moves to state 'actionFSC', regardless of current state
								distribution.add(successor * numStatesFSC + actionFSC, interval);
							}

							ipomdp.addActionLabelledChoice(productState, distribution, actionIPOMDP * numStatesFSC + actionFSC);

							// Construct the transition reward
							if (initMDPRewards != null)
								rewards.addToTransitionReward(productState, actionIPOMDP * numStatesFSC + actionFSC, initMDPRewards.getTransitionReward(stateIPOMDP, actionIPOMDP));
						}
					}
				}
			}

			for (int stateIPOMDP = 0; stateIPOMDP < numStatesIPOMDP; stateIPOMDP++) {
				for (int stateFSC = 0; stateFSC < numStatesFSC; stateFSC++) {
					int productState = (stateIPOMDP * numStatesFSC) + stateFSC;

					// Construct the observation of the state
					// This must be done after transitions have been added so that actions match for same observations
					observationList[productState] = initIPOMDP.getObservation(stateIPOMDP) * numStatesFSC + stateFSC;
				}
			}
		}
	}

	private class TransformIntoSimpleIPOMDP {
		private SimpleIPOMDP simpleIPOMDP;
		private IPOMDP<Double> initialIPOMDP;
		private int[] gadget;
		private ArrayList<Integer> traversal;
		private ArrayList<Integer>[] randomisedChoicesForState;
		private int[] observationList;
		boolean shuffleActivated;

		public TransformIntoSimpleIPOMDP(IPOMDP<Double> initialIPOMDP, MDPRewards<Double> rewards, int initialState, boolean shuffleActivated, int[] observationList) {
			this.initialIPOMDP = initialIPOMDP;
			this.observationList = observationList;
			this.shuffleActivated = shuffleActivated;
			this.simpleIPOMDP = new SimpleIPOMDP();
			this.randomisedChoicesForState = new ArrayList[initialIPOMDP.getNumStates()];
			determineSupportGraph(initialIPOMDP);
			determineObservations(initialIPOMDP);
			determineRewards(initialIPOMDP, rewards);

			this.simpleIPOMDP.initialState = gadget[initialState];
		}

		public int[] getGadgetMapping() {
			return this.gadget;
		}

		public ArrayList<Integer> getOrderOfTraversal() {
			return this.traversal;
		}

		public SimpleIPOMDP getSimpleIPOMDP() {
			return this.simpleIPOMDP;
		}

		public IPOMDP<Double> getInitialIPOMDP() {
			return this.initialIPOMDP;
		}

		public BitSet computeTransformationTarget(BitSet initTarget) {
			BitSet updatedTarget = new BitSet();
			int indexOfGoalState = initTarget.nextSetBit(0);
			while (indexOfGoalState >= 0) {
				updatedTarget.set(gadget[indexOfGoalState]);
				indexOfGoalState = initTarget.nextSetBit(indexOfGoalState + 1);
			}

			return updatedTarget;
		}

		public BitSet computeTransformationRemain(BitSet initRemain) {
			// Flip the initial bitset to get bad states
			initRemain.flip(0, initRemain.size() - 1);

			BitSet updatedRemain = new BitSet();
			int indexOfGoalState = initRemain.nextSetBit(0);
			while (indexOfGoalState >= 0 && indexOfGoalState < gadget.length) {
				updatedRemain.set(gadget[indexOfGoalState]);
				indexOfGoalState = initRemain.nextSetBit(indexOfGoalState + 1);
			}

			// Flip the updated bitset to get good states
			updatedRemain.flip(0, updatedRemain.size());
			return updatedRemain;
		}

		private void determineSupportGraph(IPOMDP<Double> ipomdp) {
			int numStates = ipomdp.getNumStates();

			// Find the exact number of states of the binary/simple variant
			int numStatesAfterProcess = 0;
			for (int s = 0; s < ipomdp.getNumStates(); s++)
				numStatesAfterProcess = numStatesAfterProcess + 2 * ipomdp.getNumChoices(s) - 1;

			// Function which maps each state of the initial IPOMDP to the "gadget" in the binary/simple variant
			gadget = new int[numStates];
			Arrays.fill(gadget, -1);

			// List of states showing the order in which states were created in the binary/simple variant
			traversal = new ArrayList<>();

			// Representation of the support graph
			ArrayList<Integer> uncertainStates = new ArrayList<Integer>();
			ArrayList<Integer> actionStates = new ArrayList<Integer>();
			SimpleDistribution[] transitions = new SimpleDistribution[numStatesAfterProcess];

			// Each observation will have a randomised list of actions
			ArrayList<Integer>[] randomisedChoicesForObservation = new ArrayList[numStates];

			int lastStateAdded = -1;
			for (int state = 0; state < ipomdp.getNumStates(); state++) {
				if (gadget[state] < 0) gadget[state] = ++lastStateAdded;

				int numChoices = ipomdp.getNumChoices(state);
				for (int dummy = 0; dummy < numChoices - 1; dummy++) {
					int currState = (dummy == 0 ? gadget[state] : ++lastStateAdded);
					traversal.add(currState);

					actionStates.add(currState);
					SimpleDistribution distribution = new SimpleDistribution();
					distribution.add(new Edge(lastStateAdded + 1, new Interval<>(-1.0, 1.0)));
					distribution.add(new Edge(lastStateAdded + numChoices, new Interval<>(-1.0, 1.0)));
					transitions[currState] = distribution;
				}

				// Randomise the list of choices for the current observation
				int obs = observationList[state];
				if (randomisedChoicesForObservation[obs] == null) {
					randomisedChoicesForObservation[obs] = new ArrayList<>();
					for (int choice = 0; choice < numChoices; choice++)
						randomisedChoicesForObservation[obs].add(choice);

					if (shuffleActivated)
						Collections.shuffle(randomisedChoicesForObservation[obs]);
				}

				// Assign the randomised list of choices to the current state
				randomisedChoicesForState[state] = randomisedChoicesForObservation[obs];

				int lastStateAddedFuture = (numChoices == 1 ? lastStateAdded : lastStateAdded + numChoices);
				for (int choice = 0; choice < numChoices; choice++) {
					int currState = (numChoices == 1 ? gadget[state] : ++lastStateAdded);
					traversal.add(currState);

					uncertainStates.add(currState);
					SimpleDistribution distribution = new SimpleDistribution();
					Iterator<Map.Entry<Integer, Interval<Double>>> iterator = ipomdp.getTransitionsIterator(state, randomisedChoicesForState[state].get(choice));
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

			simpleIPOMDP.uncertainStates = uncertainStates;
			simpleIPOMDP.actionStates = actionStates;
			simpleIPOMDP.transitions = transitions;
		}

		private void determineObservations(IPOMDP<Double> ipomdp) {
			int numStates = ipomdp.getNumStates();
			int numStatesAfterProcess = traversal.size();

			// Inverse (partial) function of the gadget function
			int[] gadget_inv = new int[numStatesAfterProcess];
			Arrays.fill(gadget_inv, -1);
			for (int s = 0; s < numStates; s++)
				gadget_inv[gadget[s]] = s;

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
				int initialObservation = observationList[initialState];
				if (freshObservations[initialObservation] < 0)
					freshObservations[initialObservation] = ++lastObservationAdded;

				indexObservation = freshObservations[initialObservation];
				observations[state] = indexObservation++;
			}

			simpleIPOMDP.observations = observations;
		}

		private void determineRewards(IPOMDP<Double> ipomdp, MDPRewards<Double> rewards) {
			int n = simpleIPOMDP.getNumStates();
			simpleIPOMDP.stateRewards = new double[n];
			simpleIPOMDP.transitionRewards = new double[2 * n];

			// If there is no reward structure
			if (rewards == null) return;

			for (int state = 0; state < ipomdp.getNumStates(); state++) {
				simpleIPOMDP.stateRewards[gadget[state]] = rewards.getStateReward(state);
			}

			for (int state = 0; state < ipomdp.getNumStates(); state++) {
				if (ipomdp.getNumChoices(state) == 1)
					simpleIPOMDP.stateRewards[gadget[state]] = simpleIPOMDP.stateRewards[gadget[state]] + rewards.getTransitionReward(state, 0);
				else {
					int currState = gadget[state];
					int numChoices = ipomdp.getNumChoices(state);
					for (int choice = 0; choice < numChoices - 2; choice++) {
						simpleIPOMDP.transitionRewards[2 * currState + 1] = rewards.getTransitionReward(state, randomisedChoicesForState[state].get(choice + 1));
						currState = simpleIPOMDP.transitions[currState].get(0).state;
					}

					simpleIPOMDP.transitionRewards[2 * currState] = rewards.getTransitionReward(state, randomisedChoicesForState[state].get(0));
					simpleIPOMDP.transitionRewards[2 * currState + 1] = rewards.getTransitionReward(state, randomisedChoicesForState[state].get(numChoices - 1));
				}
			}
		}
	}

	private class SimpleIPOMDP {
		public ArrayList<Integer> uncertainStates;
		public ArrayList<Integer> actionStates;
		public SimpleDistribution[] transitions;
		public int[] observations;
		public double[] stateRewards;
		public double[] transitionRewards;
		public int initialState;

		public SimpleIPOMDP() {
		}

		public Integer getNumStates() {
			return uncertainStates.size() + actionStates.size();
		}
	}

	static private class InducedIDTMCFromIPOMDPAndPolicy {
		static public double[] ComputeReachabilityProbabilities(SimpleIPOMDP simpleIPOMDP, double[] policy, SpecificationForSimpleIPOMDP specification) throws PrismException {
			IDTMCSimple<Double> inducedIDTMC = createInducedIDTMC(simpleIPOMDP, policy);
			return computeReachProbs(inducedIDTMC, specification);
		}

		static public double[] ComputeReachabilityRewards(SimpleIPOMDP simpleIPOMDP, double[] policy, SpecificationForSimpleIPOMDP specification) throws PrismException {
			IDTMCSimple<Double> inducedIDTMC = createInducedIDTMC(simpleIPOMDP, policy);
			MCRewards<Double> rewardsIDTMC = createRewardStructure(simpleIPOMDP, policy);
			return computeReachRewards(inducedIDTMC, rewardsIDTMC, specification);
		}

		static public ArrayList<Double> computeIntervalProbabilitiesWhichGeneratedSolution(int state, double[] main, SimpleIPOMDP simpleIPOMDP) {
			int numTransitions = simpleIPOMDP.transitions[state].size();
			ArrayList<Double> intervals = new ArrayList<>();
			boolean feasible = false;
			double epsilon = 1e-3;

			while (!feasible) {
				try {
					GRBModel model = new GRBModel(env);

					GRBLinExpr recurrence = new GRBLinExpr();
					GRBVar[] intervalProbabilities = new GRBVar[numTransitions];
					for (int i = 0; i < numTransitions; i++) {
						Edge transition = simpleIPOMDP.transitions[state].get(i);

						intervalProbabilities[i] = model.addVar(transition.interval.getLower(), transition.interval.getUpper(), 0.0, GRB.CONTINUOUS, "interval" + i);
						recurrence.addTerm(main[transition.state], intervalProbabilities[i]);
					}
					model.addConstr(recurrence, GRB.GREATER_EQUAL, main[state] - simpleIPOMDP.stateRewards[state] - epsilon, "recurrenceConstraint");
					model.addConstr(recurrence, GRB.LESS_EQUAL, main[state] - simpleIPOMDP.stateRewards[state] + epsilon, "recurrenceConstraint");

					GRBLinExpr distribution = new GRBLinExpr();
					for (int i = 0; i < numTransitions; i++)
						distribution.addTerm(1.0, intervalProbabilities[i]);
					model.addConstr(distribution, GRB.EQUAL, 1.0, "distributionConstraint");

					// Optimise the model
					model.optimize();

					// Extract the interval probabilities which verify the two equality constraints

					for (int i = 0; i < numTransitions; i++)
						intervals.add(intervalProbabilities[i].get(GRB.DoubleAttr.X));

					// Dispose of model
					model.dispose();

					// Found a feasible solution
					feasible = true;
				} catch (GRBException e) {
					epsilon = epsilon * 10;
				}
			}

			return intervals;
		}

		static private IDTMCSimple<Double> createInducedIDTMC(SimpleIPOMDP simpleIPOMDP, double[] policy) {
			// Create explicit IDTMC
			IDTMCSimple<Double> inducedIDTMC = new IDTMCSimple<>(simpleIPOMDP.getNumStates());

			// Add transitions from uncertain states
			for (int state : simpleIPOMDP.uncertainStates)
				for (Edge transition : simpleIPOMDP.transitions[state]) {
					int successor = transition.state;
					Interval<Double> interval = transition.interval;

					inducedIDTMC.setProbability(state, successor, interval);
				}

			// Add transitions from action states
			for (int state : simpleIPOMDP.actionStates)
				for (int k = 0; k <= 1; k++) {
					int successor = simpleIPOMDP.transitions[state].get(k).state;
					Interval<Double> interval = new Interval<>(policy[2 * state + k], policy[2 * state + k]);

					inducedIDTMC.setProbability(state, successor, interval);
				}

			return inducedIDTMC;
		}

		static private MCRewards<Double> createRewardStructure(SimpleIPOMDP simpleIPOMDP, double[] policy) {
			StateRewardsSimple<Double> rewards = new StateRewardsSimple<>();
			for (int s = 0; s < simpleIPOMDP.getNumStates(); s++)
				rewards.setStateReward(s, simpleIPOMDP.stateRewards[s]);

			for (int s : simpleIPOMDP.actionStates) {
				double cumulative = rewards.getStateReward(s);
				for (int k = 0; k <= 1; k++)
					cumulative = cumulative + policy[2 * s + k] * simpleIPOMDP.transitionRewards[2 * s + k];
				rewards.setStateReward(s, cumulative);
			}

			return rewards;
		}

		static private double[] computeReachProbs(IDTMC<Double> IDTMC, SpecificationForSimpleIPOMDP specification) throws PrismException {
			IDTMCModelChecker modelChecker = new IDTMCModelChecker(null);
			modelChecker.inheritSettings(modelChecker);
			modelChecker.setLog(new PrismDevNullLog());
			modelChecker.setMaxIters(2000);
			modelChecker.setErrorOnNonConverge(false);

			ModelCheckerResult res = modelChecker.computeReachProbs(IDTMC, specification.remain, specification.target, specification.minMax);
			return res.soln;
		}

		static private double[] computeReachRewards(IDTMC<Double> IDTMC, MCRewards<Double> rewards, SpecificationForSimpleIPOMDP specification) throws PrismException {
			IDTMCModelChecker modelChecker = new IDTMCModelChecker(null);
			modelChecker.inheritSettings(modelChecker);
			modelChecker.setLog(new PrismDevNullLog());
			modelChecker.setMaxIters(5000);
			modelChecker.setErrorOnNonConverge(false);
			modelChecker.setVerbosity(0);
			modelChecker.setSilentPrecomputations(true);

			ModelCheckerResult res = modelChecker.computeReachRewards(IDTMC, rewards, specification.target, specification.minMax);
			return res.soln;
		}
	}

	static private class VariableHandler {

		static public void initialiseVariables(Variables mainVariables, SimpleIPOMDP simpleIPOMDP, SpecificationForSimpleIPOMDP simpleSpecification) throws PrismException {
			int numStates = simpleIPOMDP.getNumStates();

			double[] policy = new double[2 * numStates];
			for (int s : simpleIPOMDP.uncertainStates) {
				policy[2 * s] = 1.0;
			}

			for (int s : simpleIPOMDP.actionStates) {
				policy[2 * s] = 0.5;
				policy[2 * s + 1] = 1 - policy[2 * s];
			}

			double[] main;
			if (simpleSpecification.isRewardSpecification)
				main = InducedIDTMCFromIPOMDPAndPolicy.ComputeReachabilityRewards(simpleIPOMDP, policy, simpleSpecification);
			else
				main = InducedIDTMCFromIPOMDPAndPolicy.ComputeReachabilityProbabilities(simpleIPOMDP, policy, simpleSpecification);

			ArrayList<Double>[] intervalProbabilities = new ArrayList[numStates];
			for (int state : simpleIPOMDP.uncertainStates) {
				if (simpleSpecification.target.get(state)) continue;
				if (!simpleSpecification.remain.get(state)) continue;
				if (simpleSpecification.uncertaintyQuantifier == 'A') continue;
				intervalProbabilities[state] = InducedIDTMCFromIPOMDPAndPolicy.computeIntervalProbabilitiesWhichGeneratedSolution(state, main, simpleIPOMDP);
			}

			mainVariables.policy = policy;
			mainVariables.main = main;
			mainVariables.intervalProbabilities = intervalProbabilities;
		}
	}

	private class Variables {
		public double[] policy;
		public double[] main;

		public ArrayList<Double>[] intervalProbabilities;

		public Variables() {
		}

		public Variables(double[] policy, double[] prob, ArrayList<Double>[] intervalProbabilities) {
			this.policy = policy;
			this.main = prob;
			this.intervalProbabilities = intervalProbabilities;
		}
	}

	private class Parameters {
		public double penaltyWeight;
		public double trustRegion;
		public double regionChangeFactor;
		public double regionThreshold;

		public Parameters(double penaltyWeight, double trustRegion, double regionChangeFactor, double regionThreshold) {
			this.penaltyWeight = penaltyWeight;
			this.trustRegion = trustRegion;
			this.regionChangeFactor = regionChangeFactor;
			this.regionThreshold = regionThreshold;
		}
	}

	private class SpecificationForSimpleIPOMDP {
		public BitSet remain;
		public BitSet target;
		public MinMax minMax;
		public char uncertaintyQuantifier;
		public char inequalityDirection;
		public int objectiveDirection;
		public int penaltyCoefficient;
		public boolean isRewardSpecification = true;

		public SpecificationForSimpleIPOMDP(TransformIntoSimpleIPOMDP transformationProcess, MDPRewards<Double> mdpRewards, BitSet initRemain, BitSet initTarget, MinMax minMax) {
			this.remain = transformationProcess.computeTransformationRemain(initRemain);
			this.target = transformationProcess.computeTransformationTarget(initTarget);
			this.minMax = minMax;

			if (mdpRewards == null)
				this.isRewardSpecification = false;

			initialiseForLinearProgramming(minMax);
		}

		private void initialiseForLinearProgramming(MinMax minMax) {
			if (minMax.isMax()) {
				this.inequalityDirection = GRB.GREATER_EQUAL;
				this.objectiveDirection = GRB.MAXIMIZE;
				this.penaltyCoefficient = 1;
			} else {
				this.inequalityDirection = GRB.LESS_EQUAL;
				this.objectiveDirection = GRB.MINIMIZE;
				this.penaltyCoefficient = -1;
			}

			if (minMax.isMin() == minMax.isMinUnc() || minMax.isMax() == minMax.isMaxUnc())
				this.uncertaintyQuantifier = 'E';
			else
				this.uncertaintyQuantifier = 'A';
		}
	}

	private class LinearProgrammingWithGurobi {

		private SimpleIPOMDP simpleIPOMDP;
		private SpecificationForSimpleIPOMDP specification;
		private Variables mainVariables;
		private Parameters parameters;
		private GRBModel model;
		private GRBVar mainVars[];
		private GRBVar policyVars[];
		private GRBVar penaltyAction[];
		private GRBVar penaltyUncertain[];

		public LinearProgrammingWithGurobi(SimpleIPOMDP simpleIPOMDP, SpecificationForSimpleIPOMDP specification, Variables mainVariables, Parameters parameters) throws GRBException {
			this.simpleIPOMDP = simpleIPOMDP;
			this.specification = specification;
			this.mainVariables = mainVariables;
			this.parameters = parameters;
			initialiseModelAndVariables(simpleIPOMDP);
		}

		public Variables solveAndGetOptimalVariables() throws GRBException, PrismException {
			// Add constraints about the policy
			policyMustBeObservationBased();
			policyMustBeValidDistribution();

			// Add constraints about the IPOMDP itself
			addConstraintsForGoalStates();
			addConstraintsForTrustRegions();
			addConstraintsForActionStates();
			addConstraintsForUncertainStates();

			// Add main objective
			addObjective();

			// Optimise the model
			model.optimize();

			// Extract optimal policy
			double[] policy = extractOptimalPolicy();

			// Compute the rewards/reaching probabilities given the policy
			double[] main = computeMainUsingPolicy(policy);

			// Compute the values of the interval transitions which generated the solution
			ArrayList<Double>[] intervalProbabilities = computeIntervalProbabilities(main);

			// Dispose of model
			model.dispose();

			return new Variables(policy, main, intervalProbabilities);
		}

		private void initialiseModelAndVariables(SimpleIPOMDP simpleIPOMDP) throws GRBException {
			int n = simpleIPOMDP.getNumStates();
			this.model = new GRBModel(env);
			this.mainVars = new GRBVar[n];
			this.policyVars = new GRBVar[2 * n];
			this.penaltyAction = new GRBVar[n];
			this.penaltyUncertain = new GRBVar[n];

			// Handle the main variables
			for (int s = 0; s < n; s++) {
				mainVars[s] = model.addVar((specification.isRewardSpecification ? -1e6 : 0.0), (specification.isRewardSpecification ? 1e6 : 1.0), 0.0, GRB.CONTINUOUS, "x" + s);
			}

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

			// Create the penalty variables
			for (int s = 0; s < n; s++) {
				penaltyAction[s] = model.addVar(0.0, 1e9, 0.0, GRB.CONTINUOUS, "penaltyAction" + s);
				penaltyUncertain[s] = model.addVar(0.0, 1e9, 0.0, GRB.CONTINUOUS, "penaltyUncertain" + s);
			}
		}

		private double[] extractOptimalPolicy() throws GRBException {
			double[] optimalPolicy = new double[policyVars.length];
			for (int state : simpleIPOMDP.uncertainStates) {
				optimalPolicy[2 * state] = policyVars[2 * state].get(GRB.DoubleAttr.X);
			}

			for (int state : simpleIPOMDP.actionStates)
				for (int k = 0; k <= 1; k++) {
					optimalPolicy[2 * state + k] = policyVars[2 * state + k].get(GRB.DoubleAttr.X);
				}

			return optimalPolicy;
		}

		private double[] computeMainUsingPolicy(double[] policy) throws PrismException {
			if (specification.isRewardSpecification)
				return InducedIDTMCFromIPOMDPAndPolicy.ComputeReachabilityRewards(simpleIPOMDP, policy, specification);
			else
				return InducedIDTMCFromIPOMDPAndPolicy.ComputeReachabilityProbabilities(simpleIPOMDP, policy, specification);
		}

		private ArrayList<Double>[] computeIntervalProbabilities(double[] main) {
			ArrayList<Double>[] intervalProbabilities = new ArrayList[simpleIPOMDP.getNumStates()];
			for (int state : simpleIPOMDP.uncertainStates) {
				if (specification.target.get(state)) continue;
				if (!specification.remain.get(state)) continue;
				if (specification.uncertaintyQuantifier == 'A') continue;
				intervalProbabilities[state] = InducedIDTMCFromIPOMDPAndPolicy.computeIntervalProbabilitiesWhichGeneratedSolution(state, main, simpleIPOMDP);
			}

			return intervalProbabilities;
		}

		private void addObjective() throws GRBException {
			GRBLinExpr obj = new GRBLinExpr();
			obj.addTerm(1.0, mainVars[simpleIPOMDP.initialState]);
			for (int state : simpleIPOMDP.actionStates)
				obj.addTerm(-specification.penaltyCoefficient * parameters.penaltyWeight, penaltyAction[state]);
			for (int state : simpleIPOMDP.uncertainStates)
				obj.addTerm(-specification.penaltyCoefficient * parameters.penaltyWeight, penaltyUncertain[state]);
			model.setObjective(obj, specification.objectiveDirection);
		}

		private void addConstraintsForActionStates() throws GRBException {
			for (int state : simpleIPOMDP.actionStates) {
				// If current state is part of the target set then skip
				if (specification.target.get(state)) continue;
				if (!specification.remain.get(state)) continue;

				GRBLinExpr constraint = new GRBLinExpr();
				constraint.addTerm(-1.0, mainVars[state]);
				constraint.addTerm(specification.penaltyCoefficient, penaltyAction[state]);

				double RHS = 0.0;
				for (int k = 0; k <= 1; k++) {
					int successor = simpleIPOMDP.transitions[state].get(k).state;

					// Add terms of the affine function
					constraint.addTerm(mainVariables.policy[2 * state + k], mainVars[successor]);
					constraint.addTerm(mainVariables.main[successor], policyVars[2 * state + k]);

					// Add term for the transition reward
					constraint.addTerm(simpleIPOMDP.transitionRewards[2 * state + k], policyVars[2 * state + k]);

					RHS = RHS + mainVariables.policy[2 * state + k] * mainVariables.main[successor];
				}

				RHS = RHS - simpleIPOMDP.stateRewards[state];
				model.addConstr(constraint, specification.inequalityDirection, RHS, "actionState" + state);
			}
		}

		private void addConstraintsForGoalStates() throws GRBException {
			BitSet target = specification.target;
			int indexOfGoalState = target.nextSetBit(0);
			while (indexOfGoalState >= 0) {
				GRBLinExpr constraint = new GRBLinExpr();
				constraint.addTerm(1.0, mainVars[indexOfGoalState]);
				model.addConstr(constraint, GRB.EQUAL, (specification.isRewardSpecification ? 0.0 : 1.0), "goalState" + indexOfGoalState);

				indexOfGoalState = target.nextSetBit(indexOfGoalState + 1);
			}
		}

		private void addConstraintsForTrustRegions() throws GRBException {
			int numStates = simpleIPOMDP.getNumStates();
			double[] main = mainVariables.main;
			double[] policy = mainVariables.policy;
			double trustRegion = parameters.trustRegion + 1;

			for (int s = 0; s < numStates; s++) {
				GRBLinExpr firstConstraint = new GRBLinExpr();
				firstConstraint.addTerm(1.0, mainVars[s]);
				model.addConstr(firstConstraint, GRB.GREATER_EQUAL, main[s] / trustRegion, "trustRegionMainLeft" + s);

				GRBLinExpr secondConstraint = new GRBLinExpr();
				secondConstraint.addTerm(1.0, mainVars[s]);
				model.addConstr(secondConstraint, GRB.LESS_EQUAL, main[s] * trustRegion, "trustRegionMainRight" + s);
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

		private void addConstraintsForUncertainStates() throws GRBException {
			if (specification.uncertaintyQuantifier == 'E')
				addConstraintsForUncertainStatesExistentiallyQuantified();
			else
				addConstraintsForUncertainStatesUniversallyQuantified();
		}

		private void addConstraintsForUncertainStatesExistentiallyQuantified() throws GRBException {
			for (int state : simpleIPOMDP.uncertainStates) {
				// If current state is part of the target set then skip
				if (specification.target.get(state)) continue;
				if (!specification.remain.get(state)) continue;

				int n = simpleIPOMDP.transitions[state].size();

				// Create the interval probabilities variables
				GRBVar intervalProbabilities[] = new GRBVar[n];
				GRBLinExpr distribution = new GRBLinExpr();
				for (int i = 0; i < n; i++) {
					Edge transition = simpleIPOMDP.transitions[state].get(i);
					intervalProbabilities[i] = model.addVar(transition.interval.getLower(), transition.interval.getUpper(), 0.0, GRB.CONTINUOUS, "interval" + i);
					distribution.addTerm(1.0, intervalProbabilities[i]);
				}
				model.addConstr(distribution, GRB.EQUAL, 1.0, "distribution" + state);

				// Add constraint for the recurrence relation
				GRBLinExpr constraint = new GRBLinExpr();
				constraint.addTerm(-1.0, mainVars[state]);
				constraint.addTerm(specification.penaltyCoefficient, penaltyUncertain[state]);

				double RHS = 0.0;
				for (int i = 0; i < n; i++) {
					Edge transition = simpleIPOMDP.transitions[state].get(i);
					int successor = transition.state;

					// Linearize the quadratic function
					constraint.addTerm(mainVariables.main[successor], intervalProbabilities[i]);
					constraint.addTerm(mainVariables.intervalProbabilities[state].get(i), mainVars[successor]);

					RHS = RHS + mainVariables.main[successor] * mainVariables.intervalProbabilities[state].get(i);
				}

				RHS = RHS - simpleIPOMDP.stateRewards[state];
				model.addConstr(constraint, specification.inequalityDirection, RHS, "uncertainState" + state);
			}
		}

		private void addConstraintsForUncertainStatesUniversallyQuantified() throws GRBException {
			// We have the constraints a(i) <= u(i) <= b(i) and sum_i u(i) = 1
			// We encode these into the equation C * u + g >= 0
			for (int state : simpleIPOMDP.uncertainStates) {
				// If current state is part of the target set then skip
				if (specification.target.get(state)) continue;
				if (!specification.remain.get(state)) continue;

				int n = simpleIPOMDP.transitions[state].size();
				int m = 2 * n + 2;

				// Keep track of the successor states
				ArrayList<Integer> successors = new ArrayList<>();

				// Create vector g
				double[] g = new double[m];

				// Populate successors and determine vector g
				for (int i = 0; i < n; i++) {
					Edge transition = simpleIPOMDP.transitions[state].get(i);

					successors.add(transition.state);
					g[2 * i] = -transition.interval.getLower();
					g[2 * i + 1] = transition.interval.getUpper();
				}
				g[2 * n] = -1.0;
				g[2 * n + 1] = 1.0;

				// Create the dual variables
				GRBVar dualVars[] = new GRBVar[m];
				for (int i = 0; i < m; i++) {
					dualVars[i] = model.addVar(0.0, 1e9, 0.0, GRB.CONTINUOUS, "dual" + state + "," + i);
				}

				// Introduce the probability inequality
				GRBLinExpr inequality = new GRBLinExpr();
				inequality.addTerm(-1.0, mainVars[state]);
				for (int i = 0; i < m; i++) {
					inequality.addTerm(g[i], dualVars[i]);
				}
				model.addConstr(inequality, specification.inequalityDirection, -simpleIPOMDP.stateRewards[state], "dualizationInequality" + state);

				// Introduce the dualization constraints
				for (int i = 0; i < n; i++) {
					int successor = successors.get(i);
					GRBLinExpr constraint = new GRBLinExpr();
					constraint.addTerm(1.0, mainVars[successor]);

					constraint.addTerm(1.0, dualVars[2 * i]);
					constraint.addTerm(-1.0, dualVars[2 * i + 1]);

					constraint.addTerm(1.0, dualVars[2 * n]);
					constraint.addTerm(-1.0, dualVars[2 * n + 1]);

					model.addConstr(constraint, GRB.EQUAL, 0.0, "dualizationConstraint" + state + "," + i);
				}
			}
		}

		private void policyMustBeValidDistribution() throws GRBException {
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

		private void policyMustBeObservationBased() throws GRBException {
			int n = simpleIPOMDP.getNumStates();
			int[] leaderOfObservation = new int[n];
			for (int s = n - 1; s >= 0; s--)
				leaderOfObservation[simpleIPOMDP.observations[s]] = s;

			for (int s : simpleIPOMDP.actionStates)
				for (int k = 0; k <= 1; k++) {
					int idx = leaderOfObservation[simpleIPOMDP.observations[s]];
					GRBLinExpr observationBased = new GRBLinExpr();
					observationBased.addTerm(1.0, policyVars[2 * s + k]);
					model.addConstr(observationBased, GRB.EQUAL, policyVars[2 * idx + k], "policyObservation" + s);
				}
		}
	}

	private class SolutionPoint {
		private TransformIntoSimpleIPOMDP transformationProcess;
		private SpecificationForSimpleIPOMDP specification;
		private Parameters parameters;
		private Variables variables;
		private double objective;
		private int iterationsLeft;
		private int initialState;

		public SolutionPoint() {
		}

		public SolutionPoint(TransformIntoSimpleIPOMDP transformationProcess, SpecificationForSimpleIPOMDP specification, Parameters parameters) throws PrismException {
			this.initialState = transformationProcess.simpleIPOMDP.initialState;
			this.transformationProcess = transformationProcess;
			this.specification = specification;
			this.parameters = parameters;
			this.iterationsLeft = 50;
			this.objective = (specification.isRewardSpecification ? 1e6 : 1.0);
			this.objective = (specification.objectiveDirection == GRB.MAXIMIZE ? 1 - this.objective : this.objective);
			this.variables = new Variables();
			VariableHandler.initialiseVariables(this.variables, transformationProcess.simpleIPOMDP, specification);
		}

		public boolean GetCloserTowardsOptimum() throws PrismException {
			Variables newVariables;

			if (parameters.trustRegion <= parameters.regionThreshold || iterationsLeft == 0) return false;
			else iterationsLeft = iterationsLeft - 1;

			// Solve the Linear Programming
			try {
				LinearProgrammingWithGurobi gurobiLP = new LinearProgrammingWithGurobi(transformationProcess.simpleIPOMDP, specification, variables, parameters);
				newVariables = gurobiLP.solveAndGetOptimalVariables();

				double newObjective = newVariables.main[initialState];
				if (specification.objectiveDirection * newObjective < specification.objectiveDirection * objective) {
					objective = newObjective;
					variables = newVariables;
					parameters.trustRegion = parameters.trustRegion * parameters.regionChangeFactor;
				} else {
					parameters.trustRegion = parameters.trustRegion / parameters.regionChangeFactor;
				}
				return true;
			} catch (GRBException | PrismException e) {
				System.out.println("Error solving LP... " + e.getMessage());
				return false;
			}
		}
	}

	/**
	 * Create a new IPOMDPModelChecker, inherit basic state from parent (unless null).
	 */
	public IPOMDPModelChecker(PrismComponent parent) throws PrismException {
		super(parent);
	}

	/**
	 * Compute until probabilities.
	 * i.e. compute the probability of reaching a state in {@code target}.
	 *
	 * @param ipomdp The IPOMDP
	 * @param remain Remain in these states (optional: null means "all")
	 * @param target Target states
	 * @param minMax Min/max info
	 */
	public ModelCheckerResult computeUntilProbs(IPOMDP<Double> ipomdp, BitSet remain, BitSet target, MinMax minMax) throws PrismException {
		return computeReachProbs(ipomdp, remain, target, minMax);
	}

	/**
	 * Compute reachability/until probabilities.
	 * i.e. compute the probability of reaching a state in {@code target},
	 * while remaining in those in {@code remain}.
	 *
	 * @param ipomdp The IPOMDP
	 * @param remain Remain in these states (optional: null means "all")
	 * @param target Target states
	 * @param minMax Min/max info
	 */
	public ModelCheckerResult computeReachProbs(IPOMDP<Double> ipomdp, BitSet remain, BitSet target, MinMax minMax) throws PrismException {
		// The number of states in the Finite State Controller (FSC)
		int memoryStates = 1;

		// Construct the product between the IPOMDP and the FSC
		ProductBetweenIPOMDPAndFSC product = new ProductBetweenIPOMDPAndFSC(ipomdp, null, remain, target, memoryStates);

		// Return result vector
		ModelCheckerResult res = new ModelCheckerResult();
		res.soln = new double[ipomdp.getNumStates()];
		res.soln[ipomdp.getFirstInitialState()] = applyIterativeAlgorithm(product.ipomdp, product.rewards, product.remain, product.target, product.initialState, product.observationList, minMax);
		//res.soln[ipomdp.getFirstInitialState()] = applyGeneticAlgorithm(product.ipomdp, product.rewards, product.remain, product.target, product.initialState, product.observationList, minMax);
		return res;
	}

	/**
	 * Compute expected reachability rewards.
	 *
	 * @param ipomdp     The IPOMDP
	 * @param mdpRewards The rewards
	 * @param target     Target states
	 * @param minMax     Min/max info
	 */
	public ModelCheckerResult computeReachRewards(IPOMDP<Double> ipomdp, MDPRewards<Double> mdpRewards, BitSet target, MinMax minMax) throws PrismException {
		// The number of states in the Finite State Controller (FSC)
		int memoryStates = 2;

		// By default, the agent can remain in any states
		BitSet remain = new BitSet(ipomdp.getNumStates());
		remain.flip(0, remain.size() - 1);

		// Construct the product between the IPOMDP and the FSC
		ProductBetweenIPOMDPAndFSC product = new ProductBetweenIPOMDPAndFSC(ipomdp, mdpRewards, remain, target, memoryStates);

		// Return result vector
		ModelCheckerResult res = new ModelCheckerResult();
		res.soln = new double[ipomdp.getNumStates()];
		//res.soln[ipomdp.getFirstInitialState()] = applyIterativeAlgorithm(product.ipomdp, product.rewards, product.remain, product.target, product.initialState, product.observationList, minMax);
		res.soln[ipomdp.getFirstInitialState()] = applyGeneticAlgorithm(product.ipomdp, product.rewards, product.remain, product.target, product.initialState, product.observationList, minMax);
		return res;
	}

	public double applyIterativeAlgorithm(IPOMDP<Double> ipomdp, MDPRewards<Double> mdpRewards, BitSet remain, BitSet target, int initialState, int[] observationList, MinMax minMax) throws PrismException {
		try {
			env = new GRBEnv("gurobi.log");
			env.set(GRB.IntParam.OutputFlag, 0);
			env.start();
		} catch (GRBException e) {
			throw new PrismException("Could not initialise... " + e.getMessage());
		}

		int numAttempts = 10;
		boolean hasBeenAssigned = false;
		SolutionPoint bestPoint = new SolutionPoint();
		for (int i = 0; i < numAttempts; i++) {
			// Construct the binary/simple version of the IPOMDP
			TransformIntoSimpleIPOMDP transformationProcess = new TransformIntoSimpleIPOMDP(ipomdp, mdpRewards, initialState, true, observationList);

			// Compute specification associated with the binary/simple version of the IPOMDP
			SpecificationForSimpleIPOMDP simpleSpecification = new SpecificationForSimpleIPOMDP(transformationProcess, mdpRewards, remain, target, minMax);

			// Initialise parameters
			Parameters parameters = new Parameters(1e4, 1.5, 1.5, 1e-4);

			// Create solution point
			SolutionPoint solutionPoint = new SolutionPoint(transformationProcess, simpleSpecification, parameters);

			// Converge the point
			while (solutionPoint.GetCloserTowardsOptimum() == true) ;

			// Update the best point
			if (hasBeenAssigned == false || solutionPoint.specification.objectiveDirection * solutionPoint.objective < solutionPoint.specification.objectiveDirection * bestPoint.objective) {
				hasBeenAssigned = true;
				bestPoint = solutionPoint;
			}
		}

		return bestPoint.variables.main[bestPoint.initialState];
	}

	public double applyGeneticAlgorithm(IPOMDP<Double> ipomdp, MDPRewards<Double> mdpRewards, BitSet remain, BitSet target, int initialState, int[] observationList, MinMax minMax) throws PrismException {
		try {
			env = new GRBEnv("gurobi.log");
			env.set(GRB.IntParam.OutputFlag, 0);
			env.start();
		} catch (GRBException e) {
			throw new PrismException("Could not initialise... " + e.getMessage());
		}

		int pruneIterations = 4;
		int populationSize = 32;
		ArrayList<SolutionPoint> population = new ArrayList<>();
		for (int i = 0; i < populationSize; i++) {
			// Construct the binary/simple version of the IPOMDP
			TransformIntoSimpleIPOMDP transformationProcess = new TransformIntoSimpleIPOMDP(ipomdp, mdpRewards, initialState, true, observationList);

			// Compute specification associated with the binary/simple version of the IPOMDP
			SpecificationForSimpleIPOMDP simpleSpecification = new SpecificationForSimpleIPOMDP(transformationProcess, mdpRewards, remain, target, minMax);

			// Initialise parameters
			Parameters parameters = new Parameters(1e4, 1.5, 1.5, 1e-4);

			// Add solution point to queue
			population.add(new SolutionPoint(transformationProcess, simpleSpecification, parameters));
		}

		while (population.size() > 1) {
			// First phase: advance the solution points towards the optimum
			for (int i = 0; i < population.size(); i++) {
				SolutionPoint solutionPoint = population.get(i);
				for (int iterations = 0; iterations < pruneIterations; iterations++)
					solutionPoint.GetCloserTowardsOptimum();
				population.set(i, solutionPoint);
			}

			// Second phase: prune top half of the worst solutions
			population.sort(Comparator.comparing((SolutionPoint point) -> point.objective * point.specification.objectiveDirection));
			int toBeRemoved = (population.size() + 1) / 2;
			for (int i = 0; i < toBeRemoved; i++)
				population.remove(population.size() - 1);
		}

		// Extract the remaining solution point
		SolutionPoint bestPoint = population.get(0);

		// Converge the point
		while (bestPoint.GetCloserTowardsOptimum() == true);

		return bestPoint.variables.main[bestPoint.initialState];
	}
}
