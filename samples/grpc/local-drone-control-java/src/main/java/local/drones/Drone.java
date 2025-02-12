package local.drones;

import static akka.Done.done;

import akka.Done;
import akka.actor.typed.ActorRef;
import akka.actor.typed.ActorSystem;
import akka.actor.typed.Behavior;
import akka.cluster.sharding.typed.javadsl.ClusterSharding;
import akka.cluster.sharding.typed.javadsl.Entity;
import akka.cluster.sharding.typed.javadsl.EntityTypeKey;
import akka.pattern.StatusReply;
import akka.persistence.typed.PersistenceId;
import akka.persistence.typed.javadsl.CommandHandler;
import akka.persistence.typed.javadsl.Effect;
import akka.persistence.typed.javadsl.EventHandler;
import akka.persistence.typed.javadsl.EventSourcedBehavior;
import akka.serialization.jackson.CborSerializable;
import com.fasterxml.jackson.annotation.JsonCreator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public class Drone extends EventSourcedBehavior<Drone.Command, Drone.Event, Drone.State> {

  // #commands
  /** This interface defines all the commands (messages) that the Drone actor supports. */
  interface Command extends CborSerializable {}

  /**
   * A command to report the current position (coordinates and altitude) of the drone.
   *
   * <p>It replies with `Done`, which is sent back to the caller when all the events emitted by this
   * command are successfully persisted.
   */
  public static final class ReportPosition implements Command {
    public final Position position;
    public final ActorRef<Done> replyTo;

    public ReportPosition(Position position, ActorRef<Done> replyTo) {
      this.position = position;
      this.replyTo = replyTo;
    }
  }

  /**
   * A command to query the current position (coordinates and altitude) of the drone.
   *
   * <p>It replies with a `StatusReply&lt;Position>`, which is sent back to the caller as a success
   * if the coordinates are known. If not an error is sent back.
   */
  public static final class GetCurrentPosition implements Command {
    public final ActorRef<StatusReply<Position>> replyTo;

    public GetCurrentPosition(ActorRef<StatusReply<Position>> replyTo) {
      this.replyTo = replyTo;
    }
  }

  // #commands

  // #events
  /** This interface defines all the events that the Drone supports. */
  interface Event extends CborSerializable {}

  public static final class PositionUpdated implements Event {
    public final Position position;

    @JsonCreator
    public PositionUpdated(Position position) {
      this.position = position;
    }
  }

  public static final class CoarseGrainedLocationChanged implements Event {
    public final CoarseGrainedCoordinates coordinates;

    @JsonCreator
    public CoarseGrainedLocationChanged(CoarseGrainedCoordinates coordinates) {
      this.coordinates = coordinates;
    }
  }

  // #events

  // #state
  class State implements CborSerializable {
    Optional<Position> currentPosition;
    final List<Position> historicalPositions;

    State() {
      currentPosition = Optional.empty();
      historicalPositions = new ArrayList<>();
    }

    Optional<CoarseGrainedCoordinates> coarseGrainedCoordinates() {
      return currentPosition.map(p -> CoarseGrainedCoordinates.fromCoordinates(p.coordinates));
    }
  }

  // #state

  public static final EntityTypeKey<Command> ENTITY_KEY =
      EntityTypeKey.create(Command.class, "Drone");

  private static final int LOCATION_HISTORY_LIMIT = 100;

  public static void init(ActorSystem<?> system) {
    ClusterSharding.get(system)
        .init(Entity.of(ENTITY_KEY, entityContext -> new Drone(entityContext.getEntityId())));
  }

  public static Behavior<Command> create(String entityId) {
    return new Drone(entityId);
  }

  private Drone(String entityId) {
    super(PersistenceId.of(ENTITY_KEY.name(), entityId));
  }

  @Override
  public State emptyState() {
    return new State();
  }

  // #commandHandler
  @Override
  public CommandHandler<Command, Event, State> commandHandler() {
    return newCommandHandlerBuilder()
        .forAnyState()
        .onCommand(ReportPosition.class, this::onReportPosition)
        .onCommand(GetCurrentPosition.class, this::onGetCurrentPosition)
        .build();
  }

  // #commandHandler

  // #eventHandler
  @Override
  public EventHandler<State, Event> eventHandler() {
    return newEventHandlerBuilder()
        .forAnyState()
        .onEvent(
            PositionUpdated.class,
            (state, event) -> {
              if (state.currentPosition.isPresent()) {
                state.historicalPositions.add(state.currentPosition.get());
                if (state.historicalPositions.size() > LOCATION_HISTORY_LIMIT) {
                  state.historicalPositions.remove(0);
                }
              }
              state.currentPosition = Optional.of(event.position);
              return state;
            })
        .onEvent(
            CoarseGrainedLocationChanged.class,
            (state, event) ->
                // can be derived from position, so not really updating state,
                // persisted as events for aggregation
                state)
        .build();
  }

  // #eventHandler

  // #commandHandler
  private Effect<Event, State> onReportPosition(State state, ReportPosition command) {
    if (state.currentPosition.equals(Optional.of(command.position))) {
      // already seen
      return Effect().reply(command.replyTo, done());
    } else {
      var newCoarseGrainedLocation =
          CoarseGrainedCoordinates.fromCoordinates(command.position.coordinates);
      if (state.coarseGrainedCoordinates().equals(Optional.of(newCoarseGrainedLocation))) {
        // same grid location as before
        return Effect()
            .persist(new PositionUpdated(command.position))
            .thenReply(command.replyTo, newState -> done());
      } else {
        // no previous location known or new grid location
        return Effect()
            .persist(
                Arrays.asList(
                    new PositionUpdated(command.position),
                    new CoarseGrainedLocationChanged(newCoarseGrainedLocation)))
            .thenReply(command.replyTo, newState -> done());
      }
    }
  }

  private Effect<Event, State> onGetCurrentPosition(State state, GetCurrentPosition command) {
    return state
        .currentPosition
        .map(position -> Effect().reply(command.replyTo, StatusReply.success(position)))
        .orElse(Effect().reply(command.replyTo, StatusReply.error("Position of drone is unknown")));
  }
  // #commandHandler

}
