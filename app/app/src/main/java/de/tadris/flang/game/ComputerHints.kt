package de.tadris.flang.game

import androidx.annotation.WorkerThread
import de.tadris.flang.bot.NativeCFlangEngine
import de.tadris.flang.network.DataRepository
import de.tadris.flang.network_api.model.ComputerResult
import de.tadris.flang.network_api.model.ComputerResults
import de.tadris.flang_lib.Board
import de.tadris.flang_lib.Game
import de.tadris.flang_lib.Move
import de.tadris.flang_lib.bot.evaluation.MoveEvaluation
import de.tadris.flang_lib.evaluationNumber
import kotlin.concurrent.thread
import kotlin.math.absoluteValue
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt


class ComputerHints(private val listener: HintListener) {

    private var count = 0

    fun requestHints(game: Game){
        val oldCount = ++count
        thread {
            var hints = getHints(game.currentState, safeRequestResults(game, OnlineHintSource()))
            if(oldCount != count) return@thread
            if(hints.isNotEmpty()){
                listener.onHintsResult(hints)
            }else{
                hints = getHints(game.currentState, safeRequestResults(game, OfflineHintSource(OfflineBotGameController.DEFAULT_STRENGTH)))
                if(oldCount != count) return@thread
                listener.onHintsResult(hints)
            }
        }
    }

    private fun safeRequestResults(game: Game, hintSource: ComputerHintSource): ComputerResults {
        return try{
            hintSource.findHints(game)
        }catch(e: Exception){
            e.printStackTrace()
            ComputerResults(emptyList())
        }
    }

    private fun getHints(board: Board, results: ComputerResults): List<ComputerHint> {
        val bestResultsPerAlgorithm = results.results.groupBy { it.name }.values
                .mapNotNull { result -> result.maxByOrNull { it.depth } }
        return bestResultsPerAlgorithm.flatMap { getHint(board, it) }
    }
    
    private fun getHint(board: Board, result: ComputerResult): List<ComputerHint> {
        val name = result.name + result.depth
        val moves = result.getMoveEvals(board).sortedByDescending { it.evaluation * board.atMove.evaluationNumber }
        return if(moves.isNotEmpty()){
            val goodMoves = moves.filter { if(board.atMove) it.evaluation > -100 else it.evaluation < 100 }
            if(goodMoves.size > 1){
                val sumEval = goodMoves.sumOf { it.evaluation }
                val avgEval = sumEval / goodMoves.size
                val variance2 = goodMoves.sumOf { (it.evaluation - avgEval).pow(2) }
                val variance = sqrt(variance2)
                val maxDiff = variance * 0.15
                val bestEval = goodMoves.first().evaluation
                goodMoves.subList(0, min(5, goodMoves.size)).filter { (bestEval - it.evaluation).absoluteValue < maxDiff }
                        .map { getHintFromEval(name, it, ((1 - (bestEval - it.evaluation).absoluteValue / maxDiff).pow(5)*255).toInt()) }
            }else{
                listOf(getHintFromEval(name, moves.first(), 255))
            }
        }else{
            emptyList()
        }
    }

    private fun getHintFromEval(name: String, moveEvaluation: MoveEvaluation, alpha: Int) = ComputerHint(moveEvaluation.move, name.hashCode().and(0x00ffffff).or(alpha.shl(24)))
    
    data class ComputerHint(val move: Move, val color: Int)

    interface ComputerHintSource {

        @WorkerThread
        fun findHints(game: Game): ComputerResults

    }

    class OfflineHintSource(val strength: Int) : ComputerHintSource {

        override fun findHints(game: Game): ComputerResults {
            val bot = NativeCFlangEngine(1, strength, onlyBest = false)
            val botMoves = bot.findBestMove(game.currentState)
            val result = botMoves.evaluations.joinToString(separator = ";") { it.getNotation() }
            bot.destroy()
            return ComputerResults(listOf(ComputerResult(result, "OFF", strength)))
        }
    }

    class OnlineHintSource : ComputerHintSource {

        override fun findHints(game: Game) = DataRepository.getInstance().accessOpenAPI().findComputerResults(game.getFMNv1())
        
    }

    interface HintListener {

        @WorkerThread
        fun onHintsResult(hints: List<ComputerHint>)

    }

}