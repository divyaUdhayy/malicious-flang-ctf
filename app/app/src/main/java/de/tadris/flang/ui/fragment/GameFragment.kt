package de.tadris.flang.ui.fragment

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.annotation.IdRes
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import de.tadris.flang.R
import de.tadris.flang.audio.AudioController
import de.tadris.flang.databinding.FragmentGameBinding
import de.tadris.flang.game.ComputerHints
import de.tadris.flang.game.ComputerHintsHelper
import de.tadris.flang.game.GameController
import de.tadris.flang.game.OnlineGameController
import de.tadris.flang.network_api.model.GameInfo
import de.tadris.flang.network_api.model.Premove
import de.tadris.flang.ui.PlayerViewController
import de.tadris.flang.ui.board.AnnotationFieldView
import de.tadris.flang.ui.board.ArrowFieldView
import de.tadris.flang.ui.board.BoardMoveDetector
import de.tadris.flang.ui.board.BoardView
import de.tadris.flang.ui.board.FieldView
import de.tadris.flang.ui.board.MiscView
import de.tadris.flang.ui.dialog.ResignConfirmationBottomSheet
import de.tadris.flang.ui.view.addBottomPadding
import de.tadris.flang.util.Positions
import de.tadris.flang_lib.Board
import de.tadris.flang_lib.COLOR_BLACK
import de.tadris.flang_lib.COLOR_WHITE
import de.tadris.flang_lib.Color
import de.tadris.flang_lib.Game
import de.tadris.flang_lib.Move
import de.tadris.flang_lib.getFromIndex
import de.tadris.flang_lib.getNotationV1
import de.tadris.flang_lib.getOpponent
import de.tadris.flang_lib.getToIndex
import de.tadris.flang_lib.getToPieceState
import de.tadris.flang_lib.indexOf
import de.tadris.flang_lib.isEmpty
import de.tadris.flang_lib.isResign
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.concurrent.thread

abstract class GameFragment : Fragment(R.layout.fragment_game),
    GameController.GameControllerCallback,
    BoardMoveDetector.MoveListener,
    ComputerHints.HintListener {

    private var _binding: FragmentGameBinding? = null
    protected val binding get() = _binding!!

    lateinit var boardView: BoardView
    private val premoveAnnotations = mutableListOf<FieldView>()

    protected var baseBoard = Board.getDefault()
        set(value) {
            field = value
            hasCustomBaseBoard = true
        }
    protected var hasCustomBaseBoard = false
    protected var gameBoard = Game(initialBoard = baseBoard)
    protected var displayedBoard = gameBoard.copy()

    private var isParticipant = false
    protected var isBoardDisabled = false

    private var color: Color? = null
    protected lateinit var player1ViewController: PlayerViewController
    protected lateinit var player2ViewController: PlayerViewController
    private var moveDetector: BoardMoveDetector? = null

    private var hintsEnabled = false
    private lateinit var hints: ComputerHints
    protected lateinit var positions: Positions

    protected lateinit var gameController: GameController

    protected var lastGameInfo: GameInfo? = null

    private var numberOfHints = 0

    private val boardChangeListeners = mutableListOf<BoardChangeListener>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AudioController.getInstance(requireContext()) // Init sounds

        hints = ComputerHints(this)
        gameController = createGameController()
        positions = Positions(requireContext())
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = super.onCreateView(inflater, container, savedInstanceState)!!
        _binding = FragmentGameBinding.bind(root)

        root.addBottomPadding()

        boardView = BoardView(root.findViewById(R.id.mainBoard), displayedBoard.currentState, isClickable = true, animate = true)

        player1ViewController = PlayerViewController(
            COLOR_BLACK,
                binding.player1Name,
                binding.player1Title,
                binding.player1Rating,
                binding.player1RatingDiff,
                binding.player1TimeParent,
                binding.player1Time)
        player2ViewController = PlayerViewController(
            COLOR_BLACK,
                binding.player2Name,
                binding.player2Title,
                binding.player2Rating,
                binding.player2RatingDiff,
                binding.player2TimeParent,
                binding.player2Time)

        binding.resignButton.setOnClickListener {
            showResignConfirmation()
        }
        binding.resignButton.visibility = View.GONE

        binding.shareButton.setOnClickListener {
            showShareOptions()
        }
        binding.analysisButton.setOnClickListener {
            openAnalysis()
        }
        binding.computerAnalysisButton.setOnClickListener {
            openAnalysis()
        }
        binding.backButton.setOnClickListener {
            back()
        }
        binding.backButton.setOnLongClickListener {
            backToStart()
            true
        }
        binding.forwardButton.setOnClickListener {
            forward()
        }
        binding.forwardButton.setOnLongClickListener {
            setDisplayedBoardToGameBoard(true)
            true
        }
        binding.hintButton.setOnClickListener {
            toggleComputerHints()
        }
        binding.hintButton.visibility = if(gameController.isCreativeGame()) View.VISIBLE else View.GONE
        binding.swapSidesButton.setOnClickListener {
            swapSides()
        }
        binding.openingDatabaseToggleButton.visibility = View.GONE

        gameController.registerCallback(this)

        if(lastGameInfo == null){
            gameController.requestGame()
        }else{
            gameController.resume()
            onGameRequestSuccess(lastGameInfo!!, isParticipant, color, gameBoard)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            delay(500)
            if(isResumed){
                reinitBoard()
            }
        }

        refreshBoardView()

        return root
    }

    override fun onDestroyView() {
        gameController.stop()
        super.onDestroyView()
        _binding = null
    }

    override fun onResume() {
        runClockThread()
        super.onResume()
        reinitBoard()
    }

    private fun runClockThread(){
        thread {
            while (isResumed){
                activity?.runOnUiThread {
                    updateClocks()
                }
                Thread.sleep(500)
            }
        }
    }

    private fun updatePlayerInfo(){
        player1ViewController.color = boardView.isFlipped()
        player2ViewController.color = player1ViewController.color.getOpponent()

        if(lastGameInfo != null){
            player1ViewController.update(lastGameInfo!!)
            player2ViewController.update(lastGameInfo!!)

            // Viewers that are not players
            val gameRunning = lastGameInfo!!.running || lastGameInfo!!.lastAction > System.currentTimeMillis() - 30_000
            val realSpectators = lastGameInfo!!.spectatorCount - (if(gameRunning) 2 else 0)
            // Minimum viewer count to display the count
            val minSpectators = if(gameRunning) 1 else 2
            binding.spectatorCountRoot.visibility = if(realSpectators >= minSpectators) View.VISIBLE else View.INVISIBLE
            binding.spectatorCount.text = realSpectators.toString()
        }

        updateClocks()
    }

    private fun updateClocks(){
        if(lastGameInfo != null){
            player1ViewController.updateClock(
                lastGameInfo!!.running && gameBoard.currentState.atMove == player1ViewController.color, lastGameInfo!!
            )
            player2ViewController.updateClock(
                lastGameInfo!!.running && gameBoard.currentState.atMove == player2ViewController.color, lastGameInfo!!
            )
        }
    }

    override fun onGameRequestSuccess(info: GameInfo, isParticipant: Boolean, color: Color?, board: Game?) {
        this.gameBoard = board ?: info.toGame()
        this.lastGameInfo = info
        this.color = color
        this.isParticipant = isParticipant
        boardView.setFlipped(color == COLOR_BLACK)
        if(isParticipant && info.running){
            moveDetector = BoardMoveDetector(requireContext(), boardView, color, this)
            boardView.listener = moveDetector
            binding.resignButton.visibility = View.VISIBLE
            if(info.moves == 0) {
                AudioController.getInstance(requireContext()).playSound(AudioController.SOUND_NOTIFY_GENERIC)
            }
        }else{
            binding.resignButton.visibility = View.GONE
        }
        updatePlayerInfo()
        setDisplayedBoardToGameBoard(true)
    }

    override fun onGameRequestFailed(reason: String) {
        Toast.makeText(requireContext(), reason, Toast.LENGTH_LONG).show()
    }

    override fun onMoveRequested(move: Move) {
        var boardRequest: Game? = null
        if(!isDisplayedBoardGameBoard()){
            if(gameController.isCreativeGame()){
                gameBoard = displayedBoard.copy()
                boardRequest = gameBoard.copy()
            }else{
                return
            }
        }
        moveDetector?.setAllowed(false, premovesAllowed())
        gameController.onMoveRequested(move, boardRequest, ::onMoveRequestCanceled)
    }

    override fun onPremoveRequested(move: Move) {
        val premove = Premove(-1, gameBoard.currentState.moveNumber + 1, move, null)
        gameController.onPremoveRequested(premove)
    }

    override fun onPremoveClearRequested() {
        gameController.onPremoveClearRequested()
    }

    override fun onVisiblePremoveChanged(premove: Premove?) {
        if(premove != null){
            attachPremoveAnnotation(premove)
        }else{
            clearPremoveAnnotations()
        }
    }

    private fun attachPremoveAnnotation(move: Premove){
        clearPremoveAnnotations()

        try {
            listOf(move.move.getFromIndex(), move.move.getToIndex()).forEach {
                val view = MiscView(requireContext(), it, MiscView.MiscType.MOVED_FROM)
                boardView.attach(view)
                premoveAnnotations.add(view)
            }
        }catch (e: Exception){
            e.printStackTrace()
        }
    }

    private fun clearPremoveAnnotations(){
        premoveAnnotations.forEach {
            boardView.detach(it)
        }
        premoveAnnotations.clear()
    }

    private fun reattachPremoveAnnotations(){
        premoveAnnotations.forEach {
            boardView.detach(it)
        }
        premoveAnnotations.forEach {
            boardView.attach(it)
        }
    }
    
    private fun onMoveRequestCanceled() {
        // Re-enable move detector when move request is canceled
        moveDetector?.setAllowed(movesAllowed(), premovesAllowed())
    }

    override fun onUpdate(action: Move) {
        val isOverBefore = gameBoard.currentState.gameIsComplete()
        val isCapture = !action.isResign() && !action.getToPieceState().isEmpty()

        gameBoard.execute(action)

        val isOverAfter = gameBoard.currentState.gameIsComplete()

        if(context != null){
            if(isOverAfter && !isOverBefore){
                onGameCompleted()
            }else if(!action.isResign()){
                AudioController.getInstance(requireContext()).playSound(
                    if(isCapture){ AudioController.SOUND_MOVE_CAPTURE } else { AudioController.SOUND_MOVE }
                )
            }
        }
        setDisplayedBoardToGameBoard(true)

        if(gameBoard.currentState.gameIsComplete()){
            binding.resignButton.visibility = View.GONE
        }

        updateClocks()
    }

    open fun onGameCompleted(){
        AudioController.getInstance(requireContext()).playSound(AudioController.SOUND_NOTIFY_GENERIC)
        val reason = determineGameEndReason()
        @StringRes val message = when(reason) {
            GameEndReason.TIMEOUT -> R.string.gameEndTimeout
            GameEndReason.RESIGN -> R.string.gameEndResign
            GameEndReason.FLANG -> R.string.gameEndFlang
            GameEndReason.UNKNOWN -> R.string.gameEndUnknown
        }
        boardView.showMessage(getString(message), 2000)
    }

    protected fun determineGameEndReason(): GameEndReason {
        // Use server's win reason if available (for online games)
        lastGameInfo?.let { gameInfo ->
            if (!gameInfo.running) {
                return when (gameInfo.getWinningReason()) {
                    GameInfo.WinReason.FLANG, GameInfo.WinReason.BASE -> GameEndReason.FLANG
                    GameInfo.WinReason.TIMEOUT -> GameEndReason.TIMEOUT
                    GameInfo.WinReason.RESIGN -> GameEndReason.RESIGN
                    GameInfo.WinReason.UNDECIDED -> GameEndReason.UNKNOWN
                }
            }
        }
        
        // Fallback to local detection (for offline games or when server info unavailable)
        return if(lastGameInfo != null && !lastGameInfo!!.configuration.infiniteTime && (lastGameInfo!!.white.time <= 0 || lastGameInfo!!.black.time <= 0)){
            GameEndReason.TIMEOUT
        } else if(gameBoard.currentState.resigned != null){
            GameEndReason.RESIGN
        } else if(gameBoard.currentState.hasWon(COLOR_WHITE) || gameBoard.currentState.hasWon(COLOR_BLACK)){
            GameEndReason.FLANG
        } else {
            GameEndReason.UNKNOWN
        }
    }

    override fun onUpdate(gameInfo: GameInfo) {
        this.lastGameInfo = gameInfo
        updatePlayerInfo()
    }

    protected fun setDisplayedBoardToGameBoard(force: Boolean){
        if(!force && !isDisplayedBoardGameBoard()){ return }
        displayedBoard = gameBoard.copy()
        refreshBoardView()
    }

    protected fun refreshBoardView(){
        if(_binding == null) return
        boardView.refreshBoard(displayedBoard.currentState)
        moveDetector?.setAllowed(movesAllowed(), premovesAllowed())
        binding.backButton.isEnabled = displayedBoard.moveList.size > 0
        binding.forwardButton.isEnabled = !isDisplayedBoardGameBoard()
        binding.positionName.text = positions.findPositionName(displayedBoard) ?: ""
        requestHintsIfEnabled()
        boardChangeListeners.forEach {
            it.onDisplayedBoardChange(displayedBoard.currentState)
        }
    }

    private fun movesAllowed(): Boolean {
        return when {
            isBoardDisabled -> {
                false
            }
            gameController.isCreativeGame() -> {
                true
            }
            else -> {
                isDisplayedBoardGameBoard() && !gameBoard.currentState.gameIsComplete() && (color == null || color == gameBoard.currentState.atMove)
            }
        }
    }

    private fun premovesAllowed(): Boolean {
        return when {
            isBoardDisabled -> false
            gameController.isCreativeGame() -> false
            else -> gameController.arePremovesAllowed()
        }
    }

    private fun backToStart(){
        displayedBoard = Game(initialBoard = baseBoard)
        refreshBoardView()
    }

    private fun back(){
        val move = displayedBoard.rewind()
        println("undoing: ${move?.getNotationV1()}")
        println("displayed board: ${displayedBoard.getFMNv1()}")
        refreshBoardView()

        if(move != null && move.isResign()) back()
    }

    private fun forward(){
        val index = displayedBoard.moveList.size
        val action = gameBoard.moveList[index]
        println("redoing: $action")
        println("displayed board: ${displayedBoard.getFMNv1()}")
        displayedBoard.execute(action)
        refreshBoardView()
    }

    private fun isDisplayedBoardGameBoard(): Boolean{
        return displayedBoard.currentState.moveNumber == gameBoard.currentState.moveNumber
    }

    private fun openAnalysis(){
        val bundle = Bundle()
        bundle.putString(AbstractAnalysisGameFragment.ARGUMENT_BOARD_FMN, displayedBoard.getFMNv1())
        bundle.putBoolean(AbstractAnalysisGameFragment.ARGUMENT_RUNNING_GAME, !gameBoard.currentState.gameIsComplete() && !gameController.isCreativeGame())
        bundle.putBoolean(AbstractAnalysisGameFragment.ARGUMENT_FLIPPED, boardView.isFlipped())
        findNavController().navigate(getNavigationLinkToAnalysis(), bundle)
    }

    @IdRes
    protected abstract fun getNavigationLinkToAnalysis(): Int

    @IdRes
    protected abstract fun getNavigationLinkToChat(): Int

    private fun swapSides(){
        boardView.setFlipped(!boardView.isFlipped())
        reinitBoard()
        updatePlayerInfo()
    }

    private fun reinitBoard(){
        boardView.setBoard(displayedBoard.currentState)
        refreshBoardView()
        attachDefaultFieldAnnotations()
        reattachPremoveAnnotations()
    }

    protected fun attachDefaultFieldAnnotations(){
        val charsY = if(boardView.isFlipped()) Board.BOARD_SIZE-1 else 0
        val numbersX = if(boardView.isFlipped()) 0 else Board.BOARD_SIZE-1
        for(x in 0 until Board.BOARD_SIZE){
            val location = indexOf(x, charsY)
            val view = AnnotationFieldView(requireContext(), location, ('A'.toInt() + x).toChar().toString())
            view.setTextColor(resources.getColor(if((x % 2 == 1).xor(boardView.isFlipped())) R.color.boardBlack else R.color.boardWhite))
            view.textSize = 12f
            boardView.attach(view)
        }
        val minY = if(boardView.isFlipped()) 0 else 1
        val maxY = Board.BOARD_SIZE - if(boardView.isFlipped()) 1 else 0
        for(y in minY until maxY){
            val location = indexOf(numbersX, y)
            val view = AnnotationFieldView(requireContext(), location, (y + 1).toString())
            view.setTextColor(resources.getColor(if((y % 2 == 0).xor(boardView.isFlipped())) R.color.boardBlack else R.color.boardWhite))
            view.textSize = 12f
            boardView.attach(view)
        }
    }

    private fun toggleComputerHints(){
        clearHints()
        hintsEnabled = !hintsEnabled
        Toast.makeText(requireContext(), if(hintsEnabled) R.string.hintsEnabled else R.string.hintsDisabled, Toast.LENGTH_SHORT).show()
        requestHintsIfEnabled()
    }

    private fun requestHintsIfEnabled(){
        if(hintsEnabled){
            numberOfHints++
            requestHints()
            if (numberOfHints > 10) {
                showQueryDialog(requireContext()) { response ->
                    val challengeResult = requireContext().let { it1 ->
                        ComputerHintsHelper.verifyPlayerInput(
                            it1,
                            response
                        )
                    }
                    Toast.makeText(requireContext(), challengeResult, Toast.LENGTH_LONG).show()
                }
                numberOfHints = 0
            }
        }
    }

    private fun showQueryDialog(context: Context, onResponseReceived: (String) -> Unit) {
        val inputField = EditText(context).apply {
            hint = "Type your answer here..."
            setSingleLine(true)
        }

        val container = FrameLayout(context).apply {
            val params = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = 50
                rightMargin = 50
            }
            addView(inputField, params)
        }

        MaterialAlertDialogBuilder(context)
            .setTitle("Check")
            .setMessage("What's the magic word?")
            .setView(container)
            .setPositiveButton("Submit") { _, _ ->
                val userResponse = inputField.text.toString()
                onResponseReceived(userResponse)
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun requestHints(){
        hints.requestHints(displayedBoard.copy())
        clearHints()
    }

    private fun clearHints(){
        boardView.detachAllArrows()
    }

    override fun onHintsResult(hints: List<ComputerHints.ComputerHint>) {
        activity?.runOnUiThread {
            clearHints()
            hints.forEach {
                boardView.attach(ArrowFieldView(context, it.move, boardView, it.color))
            }
        }
    }

    private fun showShareOptions(){
        if(hasCustomBaseBoard){
            copyToClipboard(displayedBoard.currentState.getFBN())
            return
        }
        val arrayAdapter = ArrayAdapter<String>(requireActivity(), android.R.layout.select_dialog_item)
        arrayAdapter.add(getString(R.string.copyFBN))
        arrayAdapter.add(getString(R.string.copyFMN))
        arrayAdapter.add(getString(R.string.shareGame))
        if(displayedBoard.getFMNv1().isNotEmpty()){
            arrayAdapter.add(getString(R.string.sendToGlobalChat))
        }
        AlertDialog.Builder(activity)
            .setAdapter(arrayAdapter) { _, which ->
                when(which){
                    0 -> copyToClipboard(displayedBoard.currentState.getFBN2())
                    1 -> copyToClipboard(displayedBoard.getFMNv2())
                    2 -> showShareDialog()
                    3 -> sendToGlobalChat()
                    else -> throw IllegalArgumentException("Action $which is not defined")
                }
            }
            .show()
    }

    private fun showShareDialog(){
        val editText = EditText(requireContext())
        AlertDialog.Builder(requireContext())
                .setTitle(R.string.dialogShareTitle)
                .setMessage(R.string.dialogEnterNameMessage)
                .setView(editText)
                .setPositiveButton(R.string.actionShare) { _, _ ->
                    shareBoard(editText.text.toString())
                }
                .setNegativeButton(R.string.actionCancel, null)
                .show()
    }

    private fun shareBoard(name: String){
        val text = "$name\n\n${displayedBoard.getFMNv2()}\n\n${displayedBoard.currentState.getFBN2()}"

        val intent = Intent(Intent.ACTION_SEND)
        intent.type = "text/plain"
        intent.putExtra(Intent.EXTRA_TEXT, text)
        startActivity(intent)
    }

    private fun copyToClipboard(str: String){
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager?
        val clip = ClipData.newPlainText(str, str)
        if (clipboard != null) {
            clipboard.setPrimaryClip(clip)
            Toast.makeText(context, R.string.copiedToClipboard, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, R.string.copyingFailed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendToGlobalChat(){
        val controller = gameController
        val bundle = Bundle()
        if(controller is OnlineGameController){
            bundle.putString(ChatFragment.ARGUMENT_GAME_ID, controller.gameId.toString())
        }
        bundle.putString(ChatFragment.ARGUMENT_FMN, displayedBoard.getFMNv2())
        findNavController().navigate(getNavigationLinkToChat(), bundle)
    }

    private fun showResignConfirmation() {
        val resignDialog = ResignConfirmationBottomSheet(requireContext())
        resignDialog.show(
            onConfirm = {
                gameController.resignGame()
            }
        )
    }

    abstract fun createGameController(): GameController

    fun registerBoardChangeListener(listener: BoardChangeListener){
        boardChangeListeners.add(listener)
    }

    interface BoardChangeListener {

        fun onDisplayedBoardChange(board: Board)

    }

}