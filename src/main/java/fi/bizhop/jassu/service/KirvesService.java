package fi.bizhop.jassu.service;

import fi.bizhop.jassu.db.*;
import fi.bizhop.jassu.exception.CardException;
import fi.bizhop.jassu.exception.KirvesGameException;
import fi.bizhop.jassu.exception.TransactionException;
import fi.bizhop.jassu.model.User;
import fi.bizhop.jassu.model.kirves.ActionLog;
import fi.bizhop.jassu.model.kirves.ActionLogItem;
import fi.bizhop.jassu.model.kirves.Game;
import fi.bizhop.jassu.model.kirves.in.GameIn;
import fi.bizhop.jassu.model.kirves.out.GameBrief;
import fi.bizhop.jassu.model.kirves.pojo.GameDataPOJO;
import fi.bizhop.jassu.util.JsonUtil;
import fi.bizhop.jassu.util.TransactionHandler;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static fi.bizhop.jassu.exception.TransactionException.Type.TIMEOUT;
import static fi.bizhop.jassu.model.kirves.Game.Action.*;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

@Service
public class KirvesService {
    private static final Logger LOG = LogManager.getLogger(KirvesService.class);

    private final UserService USER_SERVICE;
    private final KirvesGameRepo GAME_REPO;

    private final ActionLogRepo ACTION_LOG_REPO;
    private final String ACTION_LOG_KEY_PATTERN = "%d-%d";

    private final Map<Long, Game> IN_MEMORY_GAMES = new ConcurrentHashMap<>();
    private final Map<String, ActionLog> IN_MEMORY_ACTION_LOGS = new HashMap<>();

    private final TransactionHandler TRANSACTION_HANDLER = new TransactionHandler();

    public KirvesService(UserService userService, KirvesGameRepo gameRepo, ActionLogRepo actionLogRepo) {
        this.USER_SERVICE = userService;
        this.GAME_REPO = gameRepo;
        this.ACTION_LOG_REPO = actionLogRepo;
    }

    public Long init(User admin) throws CardException, KirvesGameException, TransactionException {
        Game game = new Game(admin);
        LOG.info("Created new game");
        UserDB adminDB = this.USER_SERVICE.get(admin);
        KirvesGameDB db = new KirvesGameDB();
        db.admin = adminDB;
        db.active = true;
        db.players = game.getNumberOfPlayers();
        db.canJoin = true;
        db.gameData = game.toJson();

        Long id = this.GAME_REPO.save(db).id;
        LOG.info(String.format("New game saved with id=%d", id));
        db.id = id;
        this.IN_MEMORY_GAMES.put(id, game);
        this.TRANSACTION_HANDLER.registerGame(id);
        return id;
    }

    public List<GameBrief> getActiveGames() {
        List<KirvesGameDB> activeGames = this.GAME_REPO.findByActiveTrue();
        return activeGames == null
                ? Collections.emptyList()
                : activeGames.stream().map(GameBrief::new).collect(toList());
    }

    public void joinGame(Long id, User user) throws KirvesGameException, CardException, TransactionException {
        Game game = this.getGame(id);
        game.addPlayer(user);
        this.saveGame(id, game, null, user);
        LOG.info(String.format("Added player email=%s to game id=%d", user.getEmail(), id));
    }

    public Game getGame(Long id) throws KirvesGameException, CardException, TransactionException {
        Game fromMemory = this.IN_MEMORY_GAMES.get(id);
        if(fromMemory != null) return fromMemory;

        //game not found in memory, get game from db and register TransactionHandler
        KirvesGameDB game = this.getGameDB(id);
        GameDataPOJO pojo = JsonUtil.getJavaObject(game.gameData, GameDataPOJO.class)
                .orElseThrow(() -> new KirvesGameException("Muunnos json -> GameDataPOJO ei onnistunut"));
        Game deserializedGame = new Game(pojo);
        this.IN_MEMORY_GAMES.put(id, deserializedGame);
        this.TRANSACTION_HANDLER.registerGame(id);
        return deserializedGame;
    }

    private KirvesGameDB getGameDB(Long id) throws KirvesGameException {
        return this.GAME_REPO.findByIdAndActiveTrue(id)
                .orElseThrow(() -> new KirvesGameException(String.format("Peliä ei löytynyt, id=%d", id)));
    }

    private void sleep(long delay) throws InterruptedException {
        if(delay > 0) {
            Thread.sleep(delay);
        }
    }

    public Game action(Long id, GameIn in, User user) throws CardException, TransactionException, InterruptedException, KirvesGameException {
        return this.action(id, in, user, 0);
    }

    //Use delay only for testing transaction timeout
    public Game action(Long id, GameIn in, User user, long delay) throws KirvesGameException, CardException, TransactionException, InterruptedException {
        if(in.action == null) throw new KirvesGameException("Toiminto ei voi olla tyhjä (null)");
        Game game = this.getGame(id);
        try {
            this.TRANSACTION_HANDLER.begin(id, user, game.toJson());
        } catch (TransactionException e) {
            if(e.getType() == TIMEOUT) {
                this.IN_MEMORY_GAMES.put(id, new Game(this.TRANSACTION_HANDLER.rollback(id, GameDataPOJO.class)));
                this.TRANSACTION_HANDLER.begin(id, user, game.toJson());
            } else {
                throw e;
            }
        }
        this.sleep(delay);
        try {
            this.executeAction(in, user, game, id);
        } catch (Exception e) {
            this.IN_MEMORY_GAMES.put(id, new Game(this.TRANSACTION_HANDLER.rollback(id, GameDataPOJO.class)));
            throw e;
        }
        try {
            this.TRANSACTION_HANDLER.end(id);
            this.saveGame(id, game, in, user);
            return game;
        } catch (TransactionException e) {
            //ending transaction failed, probably for timeout. Log and rollback;
            LOG.warn(String.format("Ending transaction failed (id=%d, user=%s, message=%s), rolling back", id, user.getEmail(), e.getMessage()));
            this.IN_MEMORY_GAMES.put(id, new Game(this.TRANSACTION_HANDLER.rollback(id, GameDataPOJO.class)));
            throw new TransactionException(e.getType(), "Transaktion päättäminen epäonnistui. Edellinen tilanne palautettu.");
        }
    }

    private void executeAction(GameIn in, User user, Game game, Long id) throws KirvesGameException, CardException {
        if(!game.userHasActionAvailable(user, in.action)) {
            throw new KirvesGameException(String.format("Toiminto %s ei ole mahdollinen nyt", in.action));
        }
        switch (in.action) {
            case DEAL: game.deal(user); break;
            case PLAY_CARD: game.playCard(user, in.index); break;
            case FOLD: game.fold(user); break;
            case CUT: game.cut(user, in.declineCut); break;
            case ACE_OR_TWO_DECISION: game.aceOrTwoDecision(user, in.keepExtraCard); break;
            case SPEAK: game.speak(user, in.speak); break;
            case SPEAK_SUIT: game.speakSuit(user, in.suit); break;
            case DISCARD: game.discard(user, in.index); break;
        }
    }

    public void inactivateGame(Long id, User me) throws KirvesGameException, TransactionException {
        this.TRANSACTION_HANDLER.begin(id, me, null);

        KirvesGameDB game = this.getGameDB(id);
        if(me.getEmail().equals(game.admin.email)) {
            game.active = false;
            this.GAME_REPO.save(game);
            this.IN_MEMORY_GAMES.remove(id);
            LOG.info(String.format("Inactivated game id=%d", id));
        } else {
            throw new KirvesGameException(String.format("Et voi poistaa peliä, %s ei ole pelin omistaja (gameId=%d)", me.getNickname(), id));
        }

        this.TRANSACTION_HANDLER.end(id);
    }

    private void saveGame(Long id, Game game, GameIn in, User user) throws KirvesGameException {
        KirvesGameDB gameDB = this.getGameDB(id);
        gameDB.gameData = game.toJson();
        gameDB.players = game.getNumberOfPlayers();
        gameDB.canJoin = game.getCanJoin();
        this.GAME_REPO.save(gameDB);
        this.IN_MEMORY_GAMES.put(id, game);

        if(in != null) {
            if (List.of(PLAY_CARD, FOLD, ACE_OR_TWO_DECISION, SPEAK, SPEAK_SUIT, DISCARD).contains(in.action)) {
                this.addActionLogItem(in, user, game, id);
            } else if (in.action == DEAL) {
                this.initializeActionLog(in, user, game, id);
            }
        }
    }

    private void initializeActionLog(GameIn in, User user, Game game, Long gameId) throws KirvesGameException {
        Long handId = game.incrementHandId();

        ActionLog actionLog = new ActionLog(game.toJson());
        this.saveActionLog(in, user, gameId, handId, actionLog);

        LOG.info(String.format("Action log initialized for gameId: %d, handId: %d", gameId, handId));
    }

    private void addActionLogItem(GameIn in, User user, Game game, Long gameId) throws KirvesGameException {
        Long handId = game.getCurrentHandId();

        ActionLog actionLog = this.getActionLog(gameId, game.getCurrentHandId());
        this.saveActionLog(in, user, gameId, handId, actionLog);
    }

    private void saveActionLog(GameIn in, User user, Long gameId, Long handId, ActionLog actionLog) throws KirvesGameException {
        actionLog.addItem(ActionLogItem.of(user, in));

        List<String> emails = actionLog.getItems().stream()
                .map(item -> item.getUser().getEmail())
                .collect(toList());

        List<UserDB> userDBs = this.USER_SERVICE.getByEmails(emails);
        if(userDBs == null || userDBs.isEmpty()) throw new KirvesGameException("UserDBs was empty");

        Map<String, UserDB> usersByEmail = userDBs.stream().collect(toMap(db -> db.email, identity()));

        ActionLogDB actionLogDB = actionLog.getDB(String.format(this.ACTION_LOG_KEY_PATTERN, gameId, handId), usersByEmail);
        this.ACTION_LOG_REPO.save(actionLogDB);
        this.IN_MEMORY_ACTION_LOGS.put(this.actionLogKey(gameId, handId), actionLog);
    }

    public ActionLog getActionLog(Long gameId, Long handId) throws KirvesGameException {
        ActionLog actionLog = this.IN_MEMORY_ACTION_LOGS.get(this.actionLogKey(gameId, handId));
        if(actionLog == null) {
            String key = this.actionLogKey(gameId, handId);
            ActionLogDB actionLogDB = this.ACTION_LOG_REPO.findById(key)
                    .orElseThrow(() -> new KirvesGameException(String.format("Action log not found for gameId: %d handId: %d", gameId, handId)));
            actionLog = ActionLog.of(actionLogDB);
            this.IN_MEMORY_ACTION_LOGS.put(key, actionLog);
        }
        return actionLog;
    }

    private String actionLogKey(Long gameId, Long handId) {
        return String.format(this.ACTION_LOG_KEY_PATTERN, gameId, handId);
    }
}
