package app.flux.stores.quiz

import app.flux.stores.quiz.TeamsAndQuizStateStore.State
import app.models.access.ModelFields
import app.models.quiz.QuizState
import app.models.quiz.Team
import app.models.quiz.config.QuizConfig
import app.models.quiz.QuizState.Submission
import app.models.quiz.QuizState.TimerState
import app.models.user.User
import hydro.common.time.Clock
import hydro.common.CollectionUtils.maybeGet
import hydro.common.I18n
import hydro.common.SerializingTaskQueue
import hydro.flux.action.Dispatcher
import hydro.flux.stores.AsyncEntityDerivedStateStore
import hydro.models.access.DbQuery
import hydro.models.access.DbQueryImplicits._
import hydro.models.access.JsEntityAccess
import hydro.models.access.ModelField
import hydro.models.modification.EntityModification

import scala.async.Async.async
import scala.async.Async.await
import scala.collection.immutable.Seq
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

final class TeamsAndQuizStateStore(
    implicit entityAccess: JsEntityAccess,
    i18n: I18n,
    user: User,
    dispatcher: Dispatcher,
    clock: Clock,
    quizConfig: QuizConfig,
) extends AsyncEntityDerivedStateStore[State] {

  /**
    * Queue that processes a single task at once to avoid concurrent update issues (on the same client tab).
    */
  private val updateStateQueue: SerializingTaskQueue = SerializingTaskQueue.create()

  // **************** Implementation of AsyncEntityDerivedStateStore methods **************** //
  override protected def calculateState(): Future[State] = async {
    State(
      teams = await(
        entityAccess
          .newQuery[Team]()
          .sort(DbQuery.Sorting.ascBy(ModelFields.Team.index))
          .data()),
      quizState = await(
        entityAccess
          .newQuery[QuizState]()
          .findOne(ModelFields.QuizState.id === QuizState.onlyPossibleId)) getOrElse QuizState.nullInstance,
    )
  }

  override protected def modificationImpactsState(
      entityModification: EntityModification,
      state: State,
  ): Boolean = true

  // **************** Additional public API: Read methods **************** //
  def stateOrEmpty: State = state getOrElse State.nullInstance

  // **************** Additional public API: Write methods **************** //
  def addEmptyTeam(): Future[Unit] = updateStateQueue.schedule {
    async {
      val teams = await(stateFuture).teams
      val maxIndex = if (teams.nonEmpty) teams.map(_.index).max else -1
      await(
        entityAccess.persistModifications(
          EntityModification.createAddWithRandomId(
            Team(
              name = "",
              score = 0,
              index = maxIndex + 1,
            ))))
    }
  }
  def updateName(team: Team, newName: String): Future[Unit] = updateStateQueue.schedule {
    entityAccess.persistModifications(EntityModification.createUpdateAllFields(team.copy(name = newName)))
  }
  def updateScore(team: Team, scoreDiff: Int): Future[Unit] = updateStateQueue.schedule {
    async {
      if (scoreDiff != 0) {
        val oldScore = await(stateFuture).teams.find(_.id == team.id).get.score
        val newScore = Math.max(0, oldScore + scoreDiff)
        await(
          entityAccess.persistModifications(
            EntityModification.createUpdate(team.copy(score = newScore), Seq(ModelFields.Team.score))))
      }
    }
  }
  def deleteTeam(team: Team): Future[Unit] = updateStateQueue.schedule {
    entityAccess.persistModifications(EntityModification.createRemove(team))
  }

  def goToPreviousStep(): Future[Unit] = updateStateQueue.schedule {
    StateUpsertHelper.doQuizStateUpsert(StateUpsertHelper.progressionRelatedFields)(
      StateUpsertHelper.goToPreviousStepUpdate)
  }

  def goToNextStep(): Future[Unit] = updateStateQueue.schedule {
    StateUpsertHelper.doQuizStateUpsert(StateUpsertHelper.progressionRelatedFields)(
      StateUpsertHelper.goToNextStepUpdate)
  }

  /**
    * Go to the start of the current question if it's not already there or the start of the previous question
    * otherwise
    */
  def goToPreviousQuestion(): Future[Unit] = updateStateQueue.schedule {
    StateUpsertHelper.doQuizStateUpsert(StateUpsertHelper.progressionRelatedFields)(
      StateUpsertHelper.goToPreviousQuestionUpdate)
  }

  def goToNextQuestion(): Future[Unit] = updateStateQueue.schedule {
    StateUpsertHelper.doQuizStateUpsert(StateUpsertHelper.progressionRelatedFields)(
      StateUpsertHelper.goToNextQuestionUpdate)
  }

  /**
    * Go to the start of the current round if it's not already there or the start of the previous round
    * otherwise
    */
  def goToPreviousRound(): Future[Unit] = updateStateQueue.schedule {
    StateUpsertHelper.doQuizStateUpsert(StateUpsertHelper.progressionRelatedFields)(
      StateUpsertHelper.goToPreviousRoundUpdate)
  }
  def goToNextRound(): Future[Unit] = updateStateQueue.schedule {
    StateUpsertHelper.doQuizStateUpsert(StateUpsertHelper.progressionRelatedFields)(
      StateUpsertHelper.goToNextRoundUpdate)
  }

  def doQuizStateUpdate(fieldMasks: ModelField[_, QuizState]*)(update: QuizState => QuizState): Future[Unit] =
    updateStateQueue.schedule {
      StateUpsertHelper.doQuizStateUpsert(fieldMasks.toVector)(update)
    }

  def addSubmission(submission: Submission): Future[Unit] =
    updateStateQueue.schedule {
      StateUpsertHelper.doQuizStateUpsert(Seq(ModelFields.QuizState.submissions)) { quizState =>
        quizState.copy(
          submissions = quizState.submissions :+ submission,
        )
      }
    }

  private object StateUpsertHelper {

    val progressionRelatedFields: Seq[ModelField[_, QuizState]] = Seq(
      ModelFields.QuizState.roundIndex,
      ModelFields.QuizState.questionIndex,
      ModelFields.QuizState.questionProgressIndex,
      ModelFields.QuizState.timerState,
      ModelFields.QuizState.submissions
    )

    def doQuizStateUpsert(fieldMasks: Seq[ModelField[_, QuizState]])(
        update: QuizState => QuizState): Future[Unit] =
      async {
        val quizState = await(stateFuture).quizState

        // Optimization: Only fetch state via (non-cached) entityAccess if there is a reason
        // to think it may have to be added
        if (quizState == QuizState.nullInstance) {
          val maybeQuizState = await(
            entityAccess
              .newQuery[QuizState]()
              .findOne(ModelFields.QuizState.id === QuizState.onlyPossibleId))

          maybeQuizState match {
            case None =>
              await(entityAccess.persistModifications(EntityModification.Add(update(QuizState.nullInstance))))
            case Some(quizState2) =>
              await(
                entityAccess.persistModifications(
                  EntityModification.createUpdate(update(quizState2), fieldMasks.toVector)))
          }
        } else {
          await(
            entityAccess.persistModifications(
              EntityModification.createUpdate(update(quizState), fieldMasks.toVector)))
        }
      }

    def goToPreviousStepUpdate(quizState: QuizState): QuizState = {
      quizState.roundIndex match {
        case -1 => quizState // Do nothing
        case _ =>
          quizState.maybeQuestion match {
            case None if quizState.roundIndex == 0 =>
              QuizState.nullInstance
            case None =>
              // Go to the end of the previous round
              val newRoundIndex = Math.min(quizState.roundIndex - 1, quizConfig.rounds.size - 1)
              val newRound = quizConfig.rounds(newRoundIndex)
              quizState.copy(
                roundIndex = newRoundIndex,
                questionIndex = newRound.questions.size - 1,
                questionProgressIndex = newRound.questions.lastOption.map(_.maxProgressIndex) getOrElse 0,
                timerState = TimerState.createStarted(),
                submissions = Seq(),
              )
            case Some(question) if quizState.questionProgressIndex == 0 =>
              // Go to the end of the previous question
              val newQuestionIndex = quizState.questionIndex - 1
              quizState.copy(
                questionIndex = newQuestionIndex,
                questionProgressIndex =
                  maybeGet(quizState.round.questions, newQuestionIndex)
                    .map(_.maxProgressIndex) getOrElse 0,
                timerState = TimerState.createStarted(),
                submissions = Seq(),
              )
            case Some(question) if quizState.questionProgressIndex > 0 =>
              // Decrement questionProgressIndex
              quizState.copy(
                questionProgressIndex = quizState.questionProgressIndex - 1,
                timerState = TimerState.createStarted(),
              )
          }
      }
    }

    def goToNextStepUpdate(quizState: QuizState): QuizState = {
      quizState.maybeQuestion match {
        case None =>
          goToNextQuestionUpdate(quizState)
        case Some(question) if quizState.questionProgressIndex < question.maxProgressIndex =>
          // Add and remove points
          if (quizState.questionProgressIndex == question.maxProgressIndex - 1) {
            Future(addOrRemovePoints(quizState))
          }

          // Increment questionProgressIndex
          quizState.copy(
            questionProgressIndex = quizState.questionProgressIndex + 1,
            timerState = TimerState.createStarted(),
          )
        case Some(question) if quizState.questionProgressIndex == question.maxProgressIndex =>
          goToNextQuestionUpdate(quizState)
      }
    }

    def goToPreviousQuestionUpdate(quizState: QuizState): QuizState = {
      quizState.roundIndex match {
        case -1 => quizState // Do nothing
        case _ =>
          quizState.maybeQuestion match {
            case None if quizState.roundIndex == 0 =>
              QuizState.nullInstance
            case None =>
              // Go to the start of the last question of the previous round
              val newRoundIndex = Math.min(quizState.roundIndex - 1, quizConfig.rounds.size - 1)
              val newRound = quizConfig.rounds(newRoundIndex)
              quizState.copy(
                roundIndex = newRoundIndex,
                questionIndex = newRound.questions.size - 1,
                questionProgressIndex = 0,
                timerState = TimerState.createStarted(),
                submissions = Seq(),
              )
            case Some(question) if quizState.questionProgressIndex == 0 =>
              // Go to the start of the previous question
              val newQuestionIndex = quizState.questionIndex - 1
              quizState.copy(
                questionIndex = newQuestionIndex,
                questionProgressIndex = 0,
                timerState = TimerState.createStarted(),
                submissions = Seq(),
              )
            case Some(question) if quizState.questionProgressIndex > 0 =>
              // Go to the start of the question
              quizState.copy(
                questionProgressIndex = 0,
                timerState = TimerState.createStarted(),
              )
          }
      }
    }

    def goToNextQuestionUpdate(quizState: QuizState): QuizState = {
      quizState.maybeQuestion match {
        case None =>
          if (quizState.round.questions.isEmpty) {
            // Go to next round
            goToNextRoundUpdate(quizState)
          } else {
            // Go to first question
            quizState.copy(
              questionIndex = 0,
              questionProgressIndex = 0,
              timerState = TimerState.createStarted(),
              submissions = Seq(),
            )
          }
        case Some(question) =>
          if (quizState.questionIndex == quizState.round.questions.size - 1) {
            // Go to next round
            goToNextRoundUpdate(quizState)
          } else {
            // Go to next question
            quizState.copy(
              questionIndex = quizState.questionIndex + 1,
              questionProgressIndex = 0,
              timerState = TimerState.createStarted(),
              submissions = Seq(),
            )
          }
      }
    }

    def goToPreviousRoundUpdate(quizState: QuizState): QuizState = {
      quizState.roundIndex match {
        case -1 => quizState // Do nothing
        case _ =>
          quizState.maybeQuestion match {
            case None if quizState.roundIndex == 0 =>
              QuizState.nullInstance
            case None =>
              // Go to the start of the previous round
              val newRoundIndex = Math.min(quizState.roundIndex - 1, quizConfig.rounds.size - 1)
              quizState.copy(
                roundIndex = newRoundIndex,
                questionIndex = -1,
                questionProgressIndex = 0,
                timerState = TimerState.createStarted(),
                submissions = Seq(),
              )
            case Some(question) =>
              // Go to the start of the current round
              quizState.copy(
                questionIndex = -1,
                questionProgressIndex = 0,
                timerState = TimerState.createStarted(),
                submissions = Seq(),
              )
          }
      }
    }

    def goToNextRoundUpdate(quizState: QuizState): QuizState = {
      QuizState(
        roundIndex = quizState.roundIndex + 1,
        questionProgressIndex = 0,
        timerState = TimerState.createStarted(),
        submissions = Seq(),
      )
    }

    private def addOrRemovePoints(quizState: QuizState): Unit = {
      val question = quizState.maybeQuestion.get
      if (question.isMultipleChoice) {
        for (submission <- quizState.submissions) {
          val correct = question.isCorrectAnswerIndex(submission.maybeAnswerIndex.get)
          val scoreDiff = if (correct) question.pointsToGain else question.pointsToGainOnWrongAnswer
          val team = stateOrEmpty.teams.find(_.id == submission.teamId).get
          updateScore(team, scoreDiff = scoreDiff)
        }
      }
    }
  }
}

object TeamsAndQuizStateStore {
  case class State(
      teams: Seq[Team],
      quizState: QuizState,
  )
  object State {
    val nullInstance = State(teams = Seq(), quizState = QuizState.nullInstance)
  }
}
