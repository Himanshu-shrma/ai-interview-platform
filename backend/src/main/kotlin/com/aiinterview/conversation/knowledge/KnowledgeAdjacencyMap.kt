package com.aiinterview.conversation.knowledge

import com.aiinterview.conversation.brain.Hypothesis
import com.aiinterview.conversation.brain.HypothesisStatus

/**
 * Maps known topics to adjacent unknown topics for predictive probing.
 * When a candidate demonstrates knowledge of topic X, we know what to probe next.
 */
data class AdjacentTopic(
    val topicId: String,
    val description: String,
    val probeQuestion: String,
    val bloomsLevel: Int,
    val diagnosticValue: Float,
    val isOrthogonal: Boolean = false,
)

object KnowledgeAdjacencyMap {

    private val adjacencyMap: Map<String, List<AdjacentTopic>> = mapOf(
        "hash_map_usage" to listOf(
            AdjacentTopic("hash_map_O1_why", "Understands WHY hash map is O(1) average", "What makes hash map lookups constant time?", 4, 0.8f),
            AdjacentTopic("hash_map_collision", "Knows collision handling strategies", "What happens when two keys hash to the same bucket?", 3, 0.7f),
            AdjacentTopic("hash_map_vs_array", "Can compare hash map vs sorted array trade-offs", "When would a sorted array be better than a hash map?", 5, 0.9f, isOrthogonal = true),
        ),
        "bfs_algorithm" to listOf(
            AdjacentTopic("bfs_space_complexity", "Knows BFS space cost", "What is the space complexity of BFS and why?", 4, 0.7f),
            AdjacentTopic("bfs_vs_dfs_when", "Can choose between BFS and DFS", "How would you decide between BFS and DFS for a given problem?", 5, 0.9f, isOrthogonal = true),
            AdjacentTopic("bfs_weighted_failure", "Knows BFS limitation on weighted graphs", "What happens if you use BFS on a weighted graph?", 4, 0.8f),
        ),
        "recursion_correct" to listOf(
            AdjacentTopic("recursion_stack_space", "Understands stack space implications", "What is the space cost of your recursive solution?", 4, 0.7f),
            AdjacentTopic("recursion_vs_iteration", "Can convert recursion to iteration", "How would you rewrite this iteratively?", 5, 0.8f, isOrthogonal = true),
            AdjacentTopic("recursion_memoization", "Knows when to add memoization", "How would you avoid redundant recursive calls?", 4, 0.9f),
        ),
        "dp_pattern_recognized" to listOf(
            AdjacentTopic("dp_state_definition", "Can formally define DP state", "How would you define the state for this DP problem?", 4, 0.8f),
            AdjacentTopic("dp_recurrence", "Can write the recurrence relation", "What is the recurrence relation?", 5, 0.9f),
            AdjacentTopic("dp_vs_greedy", "Knows when greedy fails vs DP", "When would a greedy approach fail here?", 5, 0.9f, isOrthogonal = true),
        ),
        "binary_tree_traversal" to listOf(
            AdjacentTopic("bst_property", "Understands BST invariant", "What property must a BST maintain?", 3, 0.6f),
            AdjacentTopic("tree_balance", "Knows why balance matters", "Why does tree balance matter for performance?", 4, 0.8f),
            AdjacentTopic("tree_vs_graph", "Can distinguish tree from general graph", "How would your approach change if this were a general graph?", 5, 0.9f, isOrthogonal = true),
        ),
        "time_complexity_stated" to listOf(
            AdjacentTopic("space_complexity", "Also considers space complexity", "What about the space complexity?", 3, 0.7f),
            AdjacentTopic("complexity_justification", "Can justify complexity claims", "Walk me through why it is that complexity.", 4, 0.9f),
            AdjacentTopic("complexity_optimization", "Sees path to better complexity", "Is there a way to improve this?", 5, 0.8f, isOrthogonal = true),
        ),
        "sorting_algorithm" to listOf(
            AdjacentTopic("sort_stability", "Understands stability in sorting", "When does sort stability matter?", 4, 0.7f),
            AdjacentTopic("sort_space_tradeoff", "Knows in-place vs extra space trade-offs", "What is the space cost of your sorting approach?", 3, 0.6f),
            AdjacentTopic("sort_when_which", "Can choose appropriate sort for context", "What sorting approach would you use for nearly-sorted data?", 5, 0.9f, isOrthogonal = true),
        ),
        "two_pointer_pattern" to listOf(
            AdjacentTopic("two_pointer_why_works", "Can explain why two pointers work", "Why does the two-pointer approach give you the correct answer?", 4, 0.9f),
            AdjacentTopic("two_pointer_when_applicable", "Knows preconditions for two pointers", "What properties does the input need for two pointers to work?", 4, 0.7f),
            AdjacentTopic("two_pointer_vs_nested", "Can compare to brute force", "How does this compare to the nested loop approach?", 3, 0.6f),
        ),
        "high_level_design_done" to listOf(
            AdjacentTopic("bottleneck_identification", "Can identify system bottlenecks", "Where is the bottleneck in this design?", 4, 0.9f),
            AdjacentTopic("data_model", "Has thought about data modeling", "How would you structure the data for this system?", 4, 0.8f),
            AdjacentTopic("consistency_vs_availability", "Understands CAP trade-offs", "If you had to choose between consistency and availability, which would you pick and why?", 5, 0.9f, isOrthogonal = true),
        ),
        "requirements_gathered" to listOf(
            AdjacentTopic("non_functional_reqs", "Considered non-functional requirements", "What non-functional requirements matter most here?", 3, 0.7f),
            AdjacentTopic("scale_estimation", "Can estimate scale numbers", "How many requests per second would you expect?", 4, 0.8f),
            AdjacentTopic("clarifying_ambiguity", "Identifies ambiguities proactively", "What assumptions are you making that we should clarify?", 4, 0.9f, isOrthogonal = true),
        ),
        "gave_action" to listOf(
            AdjacentTopic("ownership_check", "Specifies personal contribution vs team", "What was YOUR specific role in that?", 3, 0.8f),
            AdjacentTopic("result_quantified", "Quantified the outcome", "What was the measurable outcome?", 3, 0.9f),
            AdjacentTopic("retrospective", "Shows learning and self-reflection", "What would you do differently if you faced this again?", 4, 0.9f, isOrthogonal = true),
        ),
        "star_situation_given" to listOf(
            AdjacentTopic("specificity_check", "Story is specific not generic", "Can you be more specific about the timeline and scope?", 3, 0.7f),
            AdjacentTopic("stakes_clarity", "Stakes are clear", "What was at risk if this didn't go well?", 4, 0.8f),
            AdjacentTopic("timeline_context", "Has clear timeline context", "Over what timeframe did this happen?", 2, 0.5f),
        ),
    )

    fun getAdjacentTopics(knownTopicId: String): List<AdjacentTopic> =
        adjacencyMap[knownTopicId] ?: emptyList()

    fun getAllTopicGroups(): Set<String> = adjacencyMap.keys

    /**
     * Returns the highest-value unprobed adjacent topic.
     * Prefers: high diagnosticValue + not yet in knowledgeMap + isOrthogonal.
     */
    fun getNextProbe(knowledgeMap: Map<String, Float>): AdjacentTopic? {
        val knownTopics = knowledgeMap.keys
        return knownTopics
            .flatMap { known -> getAdjacentTopics(known) }
            .filter { it.topicId !in knowledgeMap }
            .distinctBy { it.topicId }
            .sortedWith(
                compareByDescending<AdjacentTopic> { it.isOrthogonal }
                    .thenByDescending { it.diagnosticValue }
                    .thenByDescending { it.bloomsLevel },
            )
            .firstOrNull()
    }

    /** Returns next best topic considering signal depletion — prefers orthogonal when current topic is exhausted. */
    fun getNextBestTopic(currentTopic: String, knowledgeMap: Map<String, Float>, signalBudget: Map<String, Float>): AdjacentTopic? {
        val currentSignal = signalBudget[currentTopic] ?: 0f
        return getAdjacentTopics(currentTopic)
            .filter { it.topicId !in knowledgeMap.keys }
            .sortedWith(compareByDescending {
                if (currentSignal > 0.6f && it.isOrthogonal) 2.0f else it.diagnosticValue
            })
            .firstOrNull()
    }

    /** Converts an adjacent topic to a testable hypothesis. */
    fun toHypothesis(topic: AdjacentTopic, turnCount: Int): Hypothesis = Hypothesis(
        id = "adj_${topic.topicId}_t$turnCount",
        claim = "Candidate understands: ${topic.description}",
        confidence = 0.6f,
        status = HypothesisStatus.OPEN,
        testStrategy = topic.probeQuestion,
        priority = if (topic.diagnosticValue >= 0.8f) 2 else 3,
        formedAtTurn = turnCount,
        bloomsLevel = topic.bloomsLevel,
    )
}
