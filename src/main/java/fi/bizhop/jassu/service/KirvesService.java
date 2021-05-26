package fi.bizhop.jassu.service;

import fi.bizhop.jassu.db.KirvesGameDB;
import fi.bizhop.jassu.db.KirvesGameRepo;
import fi.bizhop.jassu.db.UserDB;
import fi.bizhop.jassu.exception.CardException;
import fi.bizhop.jassu.exception.KirvesGameException;
import fi.bizhop.jassu.exception.TransactionException;
import fi.bizhop.jassu.model.User;
import fi.bizhop.jassu.model.kirves.Game;
import fi.bizhop.jassu.util.Transaction;
import fi.bizhop.jassu.model.kirves.in.GameIn;
import fi.bizhop.jassu.model.kirves.out.GameBrief;
import fi.bizhop.jassu.model.kirves.pojo.GameDataPOJO;
import fi.bizhop.jassu.util.JsonUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static fi.bizhop.jassu.exception.TransactionException.Type.TIMEOUT;
import static java.util.stream.Collectors.toList;

@Service
public class KirvesService {
    private static final Logger LOG = LogManager.getLogger(KirvesService.class);

    private final UserService userService;
    private final KirvesGameRepo kirvesGameRepo;

    private final Map<Long, Game> inMemoryGames = new ConcurrentHashMap<>();

    private final Transaction TX = new Transaction();

    public KirvesService(UserService userService, KirvesGameRepo kirvesGameRepo) {
        this.userService = userService;
        this.kirvesGameRepo = kirvesGameRepo;
    }

    public Long newGameForAdmin(User admin) throws CardException, KirvesGameException {
        Game game = new Game(admin);
        LOG.info("Created new game");
        UserDB adminDB = this.userService.get(admin);
        KirvesGameDB db = new KirvesGameDB();
        db.admin = adminDB;
        db.active = true;
        db.players = game.getNumberOfPlayers();
        db.canJoin = true;
        db.gameData = game.toJson();

        Long id = this.kirvesGameRepo.save(db).id;
        LOG.info(String.format("New game saved with id=%d", id));
        db.id = id;
        this.inMemoryGames.put(id, game);
        return id;
    }

    public List<GameBrief> getActiveGames() {
        List<KirvesGameDB> activeGames = this.kirvesGameRepo.findByActiveTrue();
        return activeGames == null
                ? Collections.emptyList()
                : activeGames.stream().map(GameBrief::new).collect(toList());
    }

    public void joinGame(Long id, User user) throws KirvesGameException, CardException {
        Game game = this.getGame(id);
        game.addPlayer(user);
        this.saveGame(id, game);
        LOG.info(String.format("Added player email=%s to game id=%d", user.getEmail(), id));
    }

    public Game getGame(Long id) throws KirvesGameException, CardException {
        Game fromMemory = this.inMemoryGames.get(id);
        if(fromMemory != null) return fromMemory;

        KirvesGameDB game = this.getGameDB(id);
        GameDataPOJO pojo = JsonUtil.getJavaObject(game.gameData, GameDataPOJO.class)
                .orElseThrow(() -> new KirvesGameException("Muunnos json -> GameDataPOJO ei onnistunut"));
        Game deserializedGame = new Game(pojo);
        this.inMemoryGames.put(id, deserializedGame);
        return deserializedGame;
    }

    private KirvesGameDB getGameDB(Long id) throws KirvesGameException {
        return kirvesGameRepo.findByIdAndActiveTrue(id)
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
            this.TX.begin(user, game.toJson());
        } catch (TransactionException e) {
            if(e.getType() == TIMEOUT) {
                this.inMemoryGames.put(id, new Game(this.TX.rollback(GameDataPOJO.class)));
                this.TX.begin(user, game.toJson());
            } else {
                throw e;
            }
        }
        this.TX.check(user);
        this.sleep(delay);
        try {
            this.action(in, user, game);
        } catch (Exception e) {
            this.inMemoryGames.put(id, new Game(this.TX.rollback(GameDataPOJO.class)));
            throw e;
        }
        try {
            this.TX.end();
        } catch (TransactionException e) {
            //ending transaction failed, probably for timeout. Log and rollback;
            LOG.warn(String.format("Ending transaction failed (id=%d, user=%s, message=%s), rolling back", id, user.getEmail(), e.getMessage()));
            this.inMemoryGames.put(id, new Game(this.TX.rollback(GameDataPOJO.class)));
            throw new TransactionException(e.getType(), "Transaktion päättäminen epäonnistui. Edellinen tilanne palautettu.");
        }
        this.saveGame(id, game);
        return game;
    }

    private void action(GameIn in, User user, Game game) throws KirvesGameException, CardException {
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
            case SPEAK_SUIT: game.speakSuit(user, in.valtti); break;
            case DISCARD: game.discard(user, in.index); break;
        }
    }

    public void inactivateGame(Long id, User me) throws KirvesGameException, TransactionException {
        this.TX.begin(me, null);
        this.TX.check(me);

        KirvesGameDB game = this.getGameDB(id);
        if(me.getEmail().equals(game.admin.email)) {
            game.active = false;
            this.kirvesGameRepo.save(game);
            this.inMemoryGames.remove(id);
            LOG.info(String.format("Inactivated game id=%d", id));
        } else {
            throw new KirvesGameException(String.format("Et voi poistaa peliä, %s ei ole pelin omistaja (gameId=%d)", me.getNickname(), id));
        }

        this.TX.end();
    }

    private void saveGame(Long id, Game game) throws KirvesGameException {
        KirvesGameDB gameDB = this.getGameDB(id);
        gameDB.gameData = game.toJson();
        gameDB.players = game.getNumberOfPlayers();
        gameDB.canJoin = game.getCanJoin();
        this.kirvesGameRepo.save(gameDB);
        this.inMemoryGames.put(id, game);
    }
}
