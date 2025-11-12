package com.datacomp.core;

/**
 * Node in the Huffman tree for canonical Huffman coding.
 */
public class HuffmanNode implements Comparable<HuffmanNode> {
    private final int symbol;
    private final long frequency;
    private final HuffmanNode left;
    private final HuffmanNode right;
    private final int minSymbol;  // Minimum symbol in subtree for deterministic comparison
    
    /**
     * Create a leaf node.
     */
    public HuffmanNode(int symbol, long frequency) {
        this.symbol = symbol;
        this.frequency = frequency;
        this.left = null;
        this.right = null;
        this.minSymbol = symbol;
    }
    
    /**
     * Create an internal node.
     */
    public HuffmanNode(HuffmanNode left, HuffmanNode right) {
        this.symbol = -1;
        this.frequency = left.frequency + right.frequency;
        this.left = left;
        this.right = right;
        this.minSymbol = Math.min(left.minSymbol, right.minSymbol);
    }
    
    public boolean isLeaf() {
        return left == null && right == null;
    }
    
    public int getSymbol() {
        return symbol;
    }
    
    public long getFrequency() {
        return frequency;
    }
    
    public HuffmanNode getLeft() {
        return left;
    }
    
    public HuffmanNode getRight() {
        return right;
    }
    
    @Override
    public int compareTo(HuffmanNode other) {
        // First compare by frequency
        int cmp = Long.compare(this.frequency, other.frequency);
        if (cmp != 0) return cmp;
        
        // Tie-breaker: Compare by minimum symbol in subtree
        // This ensures deterministic, stable sorting regardless of node creation order
        return Integer.compare(this.minSymbol, other.minSymbol);
    }
}

