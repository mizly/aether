package dev.aether.modules.pathfinding.pathfinder.heap;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.util.NoSuchElementException;

public final class PrimitiveMinHeap {

    private final Long2IntOpenHashMap nodeToIndexMap;

    private long[]   nodes;
    private double[] costs;
    private int      size = 0;

    public PrimitiveMinHeap(int initialCapacity) {
        nodeToIndexMap = new Long2IntOpenHashMap(initialCapacity);
        nodeToIndexMap.defaultReturnValue(-1);
        nodes = new long  [initialCapacity + 1];
        costs = new double[initialCapacity + 1];
    }

    public boolean isEmpty() { return size == 0; }
    public int     size()    { return size; }

    public void clear() {
        size = 0;
        nodeToIndexMap.clear();
    }

    public long peekMin() {
        if (size == 0) throw new NoSuchElementException();
        return nodes[1];
    }

    public double peekMinCost() {
        if (size == 0) throw new NoSuchElementException();
        return costs[1];
    }

    public boolean contains(long packedNode) {
        return nodeToIndexMap.containsKey(packedNode);
    }

    public double getCost(long packedNode) {
        int index = nodeToIndexMap.get(packedNode);
        return index == -1 ? Double.MAX_VALUE : costs[index];
    }

    public void insertOrUpdate(long packedNode, double cost) {
        int existingIndex = nodeToIndexMap.get(packedNode);

        if (existingIndex != -1) {
            if (cost < costs[existingIndex]) {
                costs[existingIndex] = cost;
                siftUp(existingIndex);
            }
        } else {
            ensureCapacity();
            size++;
            nodes[size] = packedNode;
            costs[size] = cost;
            nodeToIndexMap.put(packedNode, size);
            siftUp(size);
        }
    }

    public long extractMin() {
        if (size == 0) throw new NoSuchElementException();

        long minNode = nodes[1];
        nodeToIndexMap.remove(minNode);

        long   lastNode = nodes[size];
        double lastCost = costs[size];
        nodes[1] = lastNode;
        costs[1] = lastCost;
        size--;

        if (size > 0) {
            nodeToIndexMap.put(lastNode, 1);
            siftDown(1);
        }

        return minNode;
    }

    private void ensureCapacity() {
        if (size >= nodes.length - 1) {
            int newCap = nodes.length * 2;
            nodes = java.util.Arrays.copyOf(nodes, newCap);
            costs = java.util.Arrays.copyOf(costs, newCap);
        }
    }

    private void siftUp(int index) {
        int    current     = index;
        long   nodeToMove  = nodes[current];
        double costToMove  = costs[current];

        while (current > 1) {
            int    parentIndex = current >> 1;
            double parentCost  = costs[parentIndex];
            if (costToMove < parentCost) {
                nodes[current] = nodes[parentIndex];
                costs[current] = parentCost;
                nodeToIndexMap.put(nodes[current], current);
                current = parentIndex;
            } else {
                break;
            }
        }

        nodes[current] = nodeToMove;
        costs[current] = costToMove;
        nodeToIndexMap.put(nodeToMove, current);
    }

    private void siftDown(int index) {
        int    current    = index;
        long   nodeToMove = nodes[current];
        double costToMove = costs[current];
        int    half       = size >> 1;

        while (current <= half) {
            int    childIndex = current << 1;
            double childCost  = costs[childIndex];
            int    rightIndex = childIndex + 1;

            if (rightIndex <= size && costs[rightIndex] < childCost) {
                childIndex = rightIndex;
                childCost  = costs[rightIndex];
            }

            if (costToMove > childCost) {
                nodes[current] = nodes[childIndex];
                costs[current] = childCost;
                nodeToIndexMap.put(nodes[current], current);
                current = childIndex;
            } else {
                break;
            }
        }

        nodes[current] = nodeToMove;
        costs[current] = costToMove;
        nodeToIndexMap.put(nodeToMove, current);
    }
}
