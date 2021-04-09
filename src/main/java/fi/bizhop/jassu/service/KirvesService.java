package fi.bizhop.jassu.service;

import fi.bizhop.jassu.db.KirvesGameDB;
import fi.bizhop.jassu.db.KirvesGameRepo;
import fi.bizhop.jassu.db.UserDB;
import fi.bizhop.jassu.exception.CardException;
import fi.bizhop.jassu.exception.KirvesGameException;
import fi.bizhop.jassu.model.KirvesGame;
import fi.bizhop.jassu.model.KirvesGameBrief;
import fi.bizhop.jassu.model.KirvesGameIn;
import fi.bizhop.jassu.model.User;
import fi.bizhop.jassu.util.SerializationUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static fi.bizhop.jassu.model.KirvesGame.Action.*;
import static java.util.stream.Collectors.toList;

@Service
public class KirvesService {
    private static final Logger LOG = LogManager.getLogger(KirvesService.class);

    final UserService userService;
    final KirvesGameRepo kirvesGameRepo;

    public KirvesService(UserService userService, KirvesGameRepo kirvesGameRepo) {
        this.userService = userService;
        this.kirvesGameRepo = kirvesGameRepo;
    }

    public Long newGameForAdmin(User admin) throws CardException, KirvesGameException {
        KirvesGame game = new KirvesGame(admin);
        LOG.info("Created new game");
        UserDB adminDB = userService.get(admin);
        KirvesGameDB db = new KirvesGameDB();
        db.admin = adminDB;
        db.active = true;
        db.players = game.getNumberOfPlayers();
        db.gameData = SerializationUtil.getByteArrayObject(game)
            .orElseThrow(() -> new KirvesGameException("Unable to convert KirvesGame to gameData"));

        Long id = kirvesGameRepo.save(db).id;
        LOG.info(String.format("New game saved with id=%d", id));
        return id;
    }

    public List<KirvesGameBrief> getActiveGames() {
        List<KirvesGameDB> activeGames = kirvesGameRepo.findByActiveTrue();
        if(activeGames != null) {
            return activeGames.stream().map(KirvesGameBrief::new).collect(toList());
        } else {
            return Collections.emptyList();
        }
    }

    public void joinGame(Long id, String email) throws KirvesGameException {
        User player = this.userService.get(email);
        if(player == null) {
            throw new KirvesGameException("Email not found");
        } else {
            KirvesGame game = getGame(id);
            game.addPlayer(player);
            LOG.info(String.format("Added player email=%s to game id=%d", player.getEmail(), id));
        }
    }

    public KirvesGame getGame(Long id) throws KirvesGameException {
        Optional<KirvesGameDB> game = kirvesGameRepo.findByIdAndActiveTrue(id);
        if(game.isPresent()) {
            return SerializationUtil.getJavaObject(game.get().gameData, KirvesGame.class)
                        .orElseThrow(() -> new KirvesGameException("Unable to convert gameData to KirvesGame"));
        } else {
            throw new KirvesGameException(String.format("Game not id=%d found", id));
        }
    }

    public KirvesGame action(Long id, KirvesGameIn in, User user) throws KirvesGameException{
        KirvesGame game = this.getGame(id);
        if(in.action == DEAL) {
            if(game.userHasActionAvailable(user, DEAL)) {
                try {
                    game.deal(user);
                } catch (CardException e) {
                    throw new KirvesGameException(String.format("Unable to deal cards: %s", e.getMessage()));
                }
            } else {
                game.setMessage("You can't deal now");
            }
        }
        else if(in.action == PLAY_CARD) {
            if(game.userHasActionAvailable(user, PLAY_CARD)) {
                try {
                    game.playCard(user, in.index);
                } catch (CardException e) {
                    game.setMessage(String.format("Unable to PLAY_CARD with index %d", in.index));
                }
            } else {
                game.setMessage("It's not your turn to PLAY_CARD");
            }
        }
        else if(in.action == CUT) {
            if(game.userHasActionAvailable(user, CUT)) {
                try {
                    game.cut(user, in.declineCut);
                } catch (CardException e) {
                    game.setMessage("Unable to CUT");
                }
            } else {
                game.setMessage("It's not your turn to CUT");
            }
        }
        else if(in.action == DISCARD) {
            if(game.userHasActionAvailable(user, DISCARD)) {
                try {
                    game.discard(user, in.index);
                } catch (CardException e) {
                    game.setMessage(String.format("Unable to DISCARD with index %d", in.index));
                }
            } else {
                game.setMessage("It's not your turn to DISCARD");
            }
        }
        else if(in.action == ACE_OR_TWO_DECISION) {
            if(game.userHasActionAvailable(user, ACE_OR_TWO_DECISION)) {
                game.aceOrTwoDecision(user, in.keepExtraCard);
            } else {
                game.setMessage("It's not your turn to ACE_OR_TWO_DECISION");
            }
        }
        else if(in.action == SET_VALTTI) {
            if(game.userHasActionAvailable(user, SET_VALTTI)) {
                if("PASS".equals(in.declarePlayerEmail)) {
                    game.startNextRound(user);
                } else {
                    User declareUser = this.userService.get(in.declarePlayerEmail);
                    if (declareUser == null) {
                        game.setMessage(String.format("Declared user %s not found", in.declarePlayerEmail));
                    } else {
                        game.setValtti(user, in.valtti, declareUser);
                    }
                }
            } else {
                game.setMessage("It's not your turn to SET_VALTTI");
            }
        }
        return game;
    }

    public void inactivateGame(Long id, User me) throws KirvesGameException {
        Optional<KirvesGameDB> gameOpt = kirvesGameRepo.findByIdAndActiveTrue(id);
        if(gameOpt.isPresent()) {
            KirvesGameDB game = gameOpt.get();
            if(me.getEmail().equals(game.admin.email)) {
                game.active = false;
                kirvesGameRepo.save(game);
                LOG.info(String.format("Inactivated game id=%d", id));
            } else {
                throw new KirvesGameException(String.format("Can't delete, %s is not admin of game id=%d", me.getEmail(), id));
            }
        } else {
            throw new KirvesGameException(String.format("Game not id=%d found", id));
        }
    }
}
