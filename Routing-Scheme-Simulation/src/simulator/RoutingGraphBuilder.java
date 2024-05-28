package simulator;

import org.jgrapht.Graph;
import org.jgrapht.GraphPath;
import org.jgrapht.alg.interfaces.ShortestPathAlgorithm;
import org.jgrapht.alg.shortestpath.BFSShortestPath;
import org.jgrapht.graph.DefaultEdge;
import simulator.graphs.AdaptedFDRG;
import simulator.graphs.RPLG;

import java.util.ArrayList;
import java.util.List;

/**
 * Builder class for setting up the simulator graphs.
 */
public class RoutingGraphBuilder {
//    private double gama;
    private double gamaPrime;
    private final RPLG rplg;
    private final ArrayList<ComputerNode> core;
    private static final double EPSILON = 1E-12; // = 0.000000000001
    private static final int SINGLE_VERTEX_PATH_LEN = 0;


  //region <Constructors>

    public RoutingGraphBuilder(AdaptedFDRG rplg){
        this.rplg = rplg;
        this.core = new ArrayList<>();
    }

  //endregion

    // region <Preprocessing>

    private void computeGamaAndGamaPrime(){
        double tau = rplg.getTau();
        double gama = ((tau - 2) / ((2 * tau) - 3)) + EPSILON;
        gamaPrime = (1 - gama) / (tau - 1);
    }

    /**
     * Building the graphs core by iterating on all the graphs vertexes and looking
     * for vertexes that have a degree higher than the core degree threshold.
     * Every vertex that have a degree higher than the threshold will be inserted to the core.
     *
     * The degree threshold is calculated using the gama prim constant.
     */
    private void computeCore(){
        computeGamaAndGamaPrime();
        Graph<ComputerNode, DefaultEdge> graph = rplg.getGraph();
        double coreDegreeThreshold = Math.pow(rplg.getNodeNum(), gamaPrime) / 4;
        for(ComputerNode node : graph.vertexSet()){
            if (node.getDegree() > coreDegreeThreshold){
                core.add(node);
            }
        }
    }

    /**
     * For a given node v this method iterate on every vertex in the graphs core,
     * (AKA landmarks), gets a shortest path to each landmark and save in the table of v
     * the port index of the neighbor in a shortest path to each landmark from v is saved in
     * the table member of v.
     *
     * All the shortest paths from v to each landmark are saved in v.
     *
     * The closest landmark and its distance from v are saved in v.
     *
     * When v is a landmark:
     *  -   the closest landmark of a landmark is itself
     *  -   the shortest path from a landmark to itself is just itself
     *  -   the distance from a landmark to its closest landmark (i.e. itself) is zero
     *  -   in the table of a landmark there is no entry for a port that's connected to itself
     *      (that means that if v is a landmark then there is no entry in its tbl for itself)
     * @param v a vertex in the graph
     * @param shortestPathsFromV all the shortest paths from v to every other node in the graph
     */
    private void processCore(ComputerNode v, ShortestPathAlgorithm.SingleSourcePaths<ComputerNode, DefaultEdge> shortestPathsFromV){
        double minDistFromNodeToCore = Double.MAX_VALUE;
        ComputerNode closestLandmarkToNode = null;
        for (ComputerNode landmark : core){
            GraphPath<ComputerNode, DefaultEdge> path = shortestPathsFromV.getPath(landmark);
            if (path != null) {
                List<ComputerNode> shortestPathToLandmark = shortestPathsFromV.getPath(landmark).getVertexList();
                int distFromCurrLandmark = shortestPathToLandmark.size() - 1;   // minus 1 for the num of edges

                if (distFromCurrLandmark > SINGLE_VERTEX_PATH_LEN) {    // check if v is the current landmark
                    shortestPathToLandmark.get(0).setNeighborPortInPathToNodeInTbl(landmark, shortestPathToLandmark.get(1));
                }

                v.insertShortestPathToLandmark(landmark, shortestPathToLandmark);

                if (minDistFromNodeToCore > distFromCurrLandmark) {
                    minDistFromNodeToCore = distFromCurrLandmark;
                    closestLandmarkToNode = landmark;
                }
            }
        }
        v.setClosestLandmark(closestLandmarkToNode);
        v.setDistanceToClosestLandmark(minDistFromNodeToCore);
    }

    /**
     * Iterating on all the graphs vertexes while looking for vertexes that should
     * be included in the Ball of the given vertex v.
     * A vertex is in the ball of v if and only if the distance of it from v is smaller
     * than the distance of v from the closest landmark to v.
     * (i.e u is in ball(v) if and only if d(v,u) < d(v,l(u))
     * the vertex v itself is not included in its Ball (even tho by the definition it should be I guess)
     * @param v the vertex in the middle of the ball
     * @param pathsFromV all the shortest paths from v to every other vertex in the graph
     */
    private void findAndProcessBallOfNode(ComputerNode v, ShortestPathAlgorithm.SingleSourcePaths<ComputerNode, DefaultEdge> pathsFromV){
        for (ComputerNode u : rplg.getGraph().vertexSet()){
            if (u != v ){
                GraphPath<ComputerNode, DefaultEdge> path = pathsFromV.getPath(u);
                if (path != null) {
                    List<ComputerNode> shortestPathToU = path.getVertexList();
                    int lenOfShortestPathToU = shortestPathToU.size() - 1;
                    if (lenOfShortestPathToU < v.getDistanceToClosestLandmark()) {
                        if (shortestPathToU.size() > SINGLE_VERTEX_PATH_LEN) {
                            shortestPathToU.get(0).setNeighborPortInPathToNodeInTbl(u, shortestPathToU.get(1));
                        }
                        v.insertNodeToBall(u);
                    }
                }
            }
        }
    }

    /**
     * Returns an int array for the ports that are used in a shortest path from
     * the closest landmark to v to the vertex v itself.
     *
     * @param v a vertex in the graph.
     * @return an empty array if there is no path to v from the landmarks.
     */
    private int[] getRevPortPathFromClosestLandmark(ComputerNode v){
        ComputerNode closestLandmarkToV = v.getClosestLandmark();
        List<ComputerNode> path = v.getShortestPathToLandmark(closestLandmarkToV);
        if (path == null){
            return new int[0];
        }
        int len = path.size() - 1;
        int[] revPortPath = new int[len]; // no need for port in the last node (that is the target node)

        for (int i = 0; i < len; i++){
            revPortPath[i] = path.get(len - i).getPortIndexOfNeighbor(path.get(len - i - 1));
        }
        return revPortPath;
    }


    /**
     * Creates a new Address object for v.
     *
     *  -  if there is no path from v to any landmark then the closest landmark index
     *      member of the Address object will be -1 and the port array will be empty!
     * @param v a vertex in the graph.
     */
    private void initAddress(ComputerNode v){
        int[] portPathFromClosestLandmarkToV = getRevPortPathFromClosestLandmark(v);
        int closestLandmarkIndex = v.getClosestLandmark() != null ? v.getClosestLandmark().getNodeIndex() : -1;
        v.setAddress(new Address(v.getNodeIndex(),closestLandmarkIndex, portPathFromClosestLandmarkToV));
    }


    public void process(){
        computeCore();
        BFSShortestPath<ComputerNode, DefaultEdge> bfs = new BFSShortestPath<>(rplg.getGraph());
        for (ComputerNode v : rplg.getGraph().vertexSet()) {
            ShortestPathAlgorithm.SingleSourcePaths<ComputerNode, DefaultEdge> shortestPathsFromV =bfs.getPaths(v);
            processCore(v, shortestPathsFromV);
            findAndProcessBallOfNode(v,shortestPathsFromV);
            initAddress(v);
        }
    }
    //endregion

    /**
     * Generates a small positive double value.
     * @param scaleFactor Determines the upper limit of the epsilon value.
     * @return A small positive double near the scaleFactor magnitude.
     */
    private static double generateEpsilon(double scaleFactor) {
        return Math.random() * scaleFactor;
    }
}
