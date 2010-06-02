/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.mahout.clustering.dirichlet;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.mapred.OutputCollector;
import org.apache.mahout.clustering.WeightedVectorWritable;
import org.apache.mahout.clustering.dirichlet.models.Model;
import org.apache.mahout.clustering.dirichlet.models.ModelDistribution;
import org.apache.mahout.math.DenseVector;
import org.apache.mahout.math.Vector;
import org.apache.mahout.math.VectorWritable;
import org.apache.mahout.math.function.TimesFunction;

/**
 * Performs Bayesian mixture modeling.
 * <p/>
 * The idea is that we use a probabilistic mixture of a number of models that we use to explain some observed
 * data. The idea here is that each observed data point is assumed to have come from one of the models in the
 * mixture, but we don't know which. The way we deal with that is to use a so-called latent parameter which
 * specifies which model each data point came from.
 * <p/>
 * In addition, since this is a Bayesian clustering algorithm, we don't want to actually commit to any single
 * explanation, but rather to sample from the distribution of models and latent assignments of data points to
 * models given the observed data and the prior distributions of model parameters.
 * <p/>
 * This sampling process is initialized by taking models at random from the prior distribution for models.
 * <p/>
 * Then, we iteratively assign points to the different models using the mixture probabilities and the degree
 * of fit between the point and each model expressed as a probability that the point was generated by that
 * model.
 * <p/>
 * After points are assigned, new parameters for each model are sampled from the posterior distribution for
 * the model parameters considering all of the observed data points that were assigned to the model. Models
 * without any data points are also sampled, but since they have no points assigned, the new samples are
 * effectively taken from the prior distribution for model parameters.
 * <p/>
 * The result is a number of samples that represent mixing probabilities, models and assignment of points to
 * models. If the total number of possible models is substantially larger than the number that ever have
 * points assigned to them, then this algorithm provides a (nearly) non-parametric clustering algorithm.
 * <p/>
 * These samples can give us interesting information that is lacking from a normal clustering that consists of
 * a single assignment of points to clusters. Firstly, by examining the number of models in each sample that
 * actually has any points assigned to it, we can get information about how many models (clusters) that the
 * data support.
 * <p/>
 * Morevoer, by examining how often two points are assigned to the same model, we can get an approximate
 * measure of how likely these points are to be explained by the same model. Such soft membership information
 * is difficult to come by with conventional clustering methods.
 * <p/>
 * Finally, we can get an idea of the stability of how the data can be described. Typically, aspects of the
 * data with lots of data available wind up with stable descriptions while at the edges, there are aspects
 * that are phenomena that we can't really commit to a solid description, but it is still clear that the well
 * supported explanations are insufficient to explain these additional aspects.
 * <p/>
 * One thing that can be difficult about these samples is that we can't always assign a correlation between
 * the models in the different samples. Probably the best way to do this is to look for overlap in the
 * assignments of data observations to the different models.
 * <p/>
 * 
 * <pre>
 *    \theta_i ~ prior()
 *    \lambda_i ~ Dirichlet(\alpha_0)
 *    z_j ~ Multinomial( \lambda )
 *    x_j ~ model(\theta_i)
 * </pre>
 */
public class DirichletClusterer<O> {

  // observed data
  private final List<O> sampleData;

  // the ModelDistribution for the computation
  private final ModelDistribution<O> modelFactory;

  // the state of the clustering process
  private final DirichletState<O> state;

  private final int thin;

  private final int burnin;

  private final int numClusters;

  private final List<Model<O>[]> clusterSamples = new ArrayList<Model<O>[]>();

  private boolean emitMostLikely;

  private double threshold;

  /**
   * Create a new instance on the sample data with the given additional parameters
   * 
   * @param sampleData
   *          the observed data to be clustered
   * @param modelFactory
   *          the ModelDistribution to use
   * @param alpha0
   *          the double value for the beta distributions
   * @param numClusters
   *          the int number of clusters
   * @param thin
   *          the int thinning interval, used to report every n iterations
   * @param burnin
   *          the int burnin interval, used to suppress early iterations
   */
  public DirichletClusterer(List<O> sampleData,
                            ModelDistribution<O> modelFactory,
                            double alpha0,
                            int numClusters,
                            int thin,
                            int burnin) {
    this.sampleData = sampleData;
    this.modelFactory = modelFactory;
    this.thin = thin;
    this.burnin = burnin;
    this.numClusters = numClusters;
    state = new DirichletState<O>(modelFactory, numClusters, alpha0);
  }

  /**
   * This constructor only used by DirichletClusterMapper for setting up clustering params
   * @param emitMostLikely
   * @param threshold
   */
  public DirichletClusterer(boolean emitMostLikely, double threshold) {
    this.sampleData = null;
    this.modelFactory = null;
    this.thin = 0;
    this.burnin = 0;
    this.numClusters = 0;
    this.state = null;
    this.emitMostLikely = emitMostLikely;
    this.threshold = threshold;
  }

  /**
   * Iterate over the sample data, obtaining cluster samples periodically and returning them.
   * 
   * @param numIterations
   *          the int number of iterations to perform
   * @return a List<List<Model<Observation>>> of the observed models
   */
  public List<Model<O>[]> cluster(int numIterations) {
    for (int iteration = 0; iteration < numIterations; iteration++) {
      iterate(iteration, state);
    }
    return clusterSamples;
  }

  /**
   * Perform one iteration of the clustering process, iterating over the samples to build a new array of
   * models, then updating the state for the next iteration
   * 
   * @param state
   *          the DirichletState<Observation> of this iteration
   */
  private void iterate(int iteration, DirichletState<O> state) {

    // create new posterior models
    Model<O>[] newModels = modelFactory.sampleFromPosterior(state.getModels());

    // iterate over the samples, assigning each to a model
    for (O x : sampleData) {
      // compute normalized vector of probabilities that x is described by each model
      Vector pi = normalizedProbabilities(state, x);
      // then pick one cluster by sampling a Multinomial distribution based upon them
      // see: http://en.wikipedia.org/wiki/Multinomial_distribution
      int k = UncommonDistributions.rMultinom(pi);
      // ask the selected model to observe the datum
      newModels[k].observe(x);
    }

    // periodically add models to the cluster samples after the burn-in period
    if ((iteration >= burnin) && (iteration % thin == 0)) {
      clusterSamples.add(newModels);
    }
    // update the state from the new models
    state.update(newModels);
  }

  /**
   * Compute a normalized vector of probabilities that x is described by each model using the mixture and the
   * model pdfs
   * 
   * @param state
   *          the DirichletState<Observation> of this iteration
   * @param x
   *          an Observation
   * @return the Vector of probabilities
   */
  private Vector normalizedProbabilities(DirichletState<O> state, O x) {
    Vector pi = new DenseVector(numClusters);
    double max = 0;
    for (int k = 0; k < numClusters; k++) {
      double p = state.adjustedProbability(x, k);
      pi.set(k, p);
      if (max < p) {
        max = p;
      }
    }
    // normalize the probabilities by largest observed value
    pi.assign(new TimesFunction(), 1.0 / max);
    return pi;
  }

  public void emitPointToClusters(VectorWritable point, List<DirichletCluster<VectorWritable>> clusters,
      OutputCollector<IntWritable, WeightedVectorWritable> output) throws IOException {
    Vector pi = new DenseVector(clusters.size());
    for (int i = 0; i < clusters.size(); i++) {
      pi.set(i, clusters.get(i).getModel().pdf(point));
    }
    pi = pi.divide(pi.zSum());
    if (emitMostLikely) {
      emitMostLikelyCluster(point, clusters, pi, output);
    } else {
      emitAllClusters(point, clusters, pi, output);
    }
  }

  /**
   * Emit the point to the most likely cluster
   * @param point
   * @param pi the normalized pdf Vector for the point
   * @param output
   * @throws IOException
   */
  void emitMostLikelyCluster(VectorWritable point, List<DirichletCluster<VectorWritable>> clusters, Vector pi,
      OutputCollector<IntWritable, WeightedVectorWritable> output) throws IOException {
    int clusterId = -1;
    double clusterPdf = 0;
    for (int i = 0; i < clusters.size(); i++) {
      double pdf = pi.get(i);
      if (pdf > clusterPdf) {
        clusterId = i;
        clusterPdf = pdf;
      }
    }
    //System.out.println(clusterId + ": " + ClusterBase.formatVector(vector.get(), null));
    output.collect(new IntWritable(clusterId), new WeightedVectorWritable(clusterPdf, point));
  }

  void emitAllClusters(VectorWritable point, List<DirichletCluster<VectorWritable>> clusters, Vector pi,
      OutputCollector<IntWritable, WeightedVectorWritable> output) throws IOException {
    for (int i = 0; i < clusters.size(); i++) {
      double pdf = pi.get(i);
      if (pdf > threshold && clusters.get(i).getTotalCount() > 0) {
        //System.out.println(i + ": " + ClusterBase.formatVector(vector.get(), null));
        output.collect(new IntWritable(i), new WeightedVectorWritable(pdf, point));
      }
    }
  }

  /**
   * Create a new instance on the sample data with the given additional parameters
   * 
   * @param points
   *          the observed data to be clustered
   * @param modelFactory
   *          the ModelDistribution to use
   * @param alpha0
   *          the double value for the beta distributions
   * @param numClusters
   *          the int number of clusters
   * @param thin
   *          the int thinning interval, used to report every n iterations
   * @param burnin
   *          the int burnin interval, used to suppress early iterations
   * @param numIterations
   *          number of iterations to be performed
   */
  public static List<Model<Vector>[]> clusterPoints(List<Vector> points, 
                                                    ModelDistribution<Vector> modelFactory,
                                                    double alpha0,
                                                    int numClusters,
                                                    int thin,
                                                    int burnin,
                                                    int numIterations) {
    DirichletClusterer<Vector> clusterer =
        new DirichletClusterer<Vector>(points, modelFactory, alpha0, numClusters, thin, burnin);
    return clusterer.cluster(numIterations);

  }
}
