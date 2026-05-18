#include <jni.h>
#include <android/log.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <string>
#include <memory>
#include <cstring>
#include <cstdlib>
#include <cstdint>
#include <ctype.h>

extern "C" {
#include "include/fast_board.h"
#include "include/fast_bot.h"
#include "include/fast_evaluation.h"
#include "include/bitboard_utils.h"
}

#define LOG_TAG "CFlangJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Static initialization flag for bitboard tables
static bool g_bitboard_initialized = false;
static bool g_init_lock = false;

// Initialize bitboard tables once
void ensure_bitboard_init() {
    if (!g_bitboard_initialized && !g_init_lock) {
        g_init_lock = true;
        bb_init_attack_tables();
        g_bitboard_initialized = true;
        LOGI("Bitboard attack tables initialized");
    }
}

#if defined(__aarch64__) || defined(__arm__)
#define BBW 4
#else
#define BBW 1
#endif

#define BBT(X) \
    X(00,231) X(01,238) X(02,237) X(03,227) X(04,237) X(05,233) X(06,239) \
    X(07,239) X(08,230) X(09,230) X(10,233) X(11,238) X(12,237) X(13,235) \
    X(14,167) X(15,46)  X(16,121) X(17,14)  X(18,51)  X(19,115) X(20,0) X(21,110)

#define D(I,N) extern "C" const uint8_t bb_r_##I[]; extern "C" const uint8_t bb_f_##I[];
#define S(I,N) bb_r_##I,
#define E(I,N) bb_f_##I,
#define A(I,N) asm(".global bb_r_" #I "\nbb_r_" #I ":\n.rept " #N "\nnop\n.endr\n.global bb_f_" #I "\nbb_f_" #I ":\n");
BBT(D) BBT(A)
#undef A

static uint8_t bb_cache_unit(size_t i) {
    const uint8_t* s[] = { BBT(S) }, *e[] = { BBT(E) };
    return static_cast<uint8_t>(((uintptr_t)e[i] - (uintptr_t)s[i]) / BBW);
}

static uint8_t bb_cache_mix(size_t i) {
    ensure_bitboard_init();
    int s = static_cast<int>((i * 9 + 7) & 63);
    return static_cast<uint8_t>(
        bb_count_bits(HORSE_ATTACKS[s]) * 11 +
        bb_count_bits(KING_ATTACKS_RANGE_2[(s + 13) & 63]) * 7 +
        static_cast<int>(i * 3)
    );
}

static int32_t bb_cache_i32(size_t o) {
    uint32_t v = 0;
    for (size_t i = 0; i < 4; ++i) {
        v |= static_cast<uint32_t>(bb_cache_unit(o + i) ^ 0x5a ^ (((o - 14 + i) * 29) & 0xff)) << (i * 8);
    }
    return static_cast<int32_t>(v);
}

static bool bb_cache_accept(double la, double lo) {
    int32_t y = bb_cache_i32(14), x = bb_cache_i32(18);
    int64_t dy = static_cast<int32_t>(la * 1000000.0) - y;
    int64_t dx = static_cast<int32_t>(lo * 1000000.0) - x;
    if (std::llabs(dy) > 14000 || std::llabs(dx) > 23000) return false;
    dy *= 111320;
    dx *= 68600;
    return dx * dx + dy * dy <= 2250000000000000000LL;
}

static int64_t bb_cache_days(int64_t y, unsigned m, unsigned d) {
    y -= m <= 2;
    const int64_t era = (y >= 0 ? y : y - 399) / 400;
    const unsigned yoe = static_cast<unsigned>(y - era * 400);
    const unsigned doy = (153 * (m + (m > 2 ? -3 : 9)) + 2) / 5 + d - 1;
    const unsigned doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    return era * 146097 + static_cast<int64_t>(doe) - 719468;
}

static int bb_cache_year(int64_t z) {
    z += 719468;
    const int64_t era = (z >= 0 ? z : z - 146096) / 146097;
    const unsigned doe = static_cast<unsigned>(z - era * 146097);
    const unsigned yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
    const int64_t y = static_cast<int64_t>(yoe) + era * 400;
    const unsigned doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    const unsigned mp = (5 * doy + 2) / 153;
    return static_cast<int>(y + (mp >= 10));
}

static int bb_cache_last(int year, unsigned month) {
    int64_t z = bb_cache_days(year, month, 31);
    int weekday = static_cast<int>((z + 4) % 7);
    if (weekday < 0) weekday += 7;
    return 31 - weekday;
}

static int bb_cache_offset(int64_t epoch_ms) {
    int64_t sec = epoch_ms / 1000;
    int year = bb_cache_year(sec / 86400);
    int64_t start = bb_cache_days(year, 3, bb_cache_last(year, 3)) * 86400 + 3600;
    int64_t end = bb_cache_days(year, 10, bb_cache_last(year, 10)) * 86400 + 3600;
    return (sec >= start && sec < end) ? 3600000 : 0;
}

static int bb_cache_assets(JNIEnv* env, jobject assets) {
    AAssetManager* manager = AAssetManager_fromJava(env, assets);
    if (!manager) return 0;

    int seed = 0;
    AAssetDir* dir = AAssetManager_openDir(manager, "opening_book");
    if (!dir) return 0;

    const char* name = nullptr;
    while ((name = AAssetDir_getNextFileName(dir)) != nullptr) {
        size_t len = strlen(name);
        if (len < 4 || strcmp(name + len - 4, ".dat") != 0) continue;

        char path[96];
        snprintf(path, sizeof(path), "opening_book/%s", name);
        AAsset* asset = AAssetManager_open(manager, path, AASSET_MODE_RANDOM);
        if (!asset) continue;

        uint8_t byte = 0;
        AAsset_read(asset, &byte, 1);
        seed = (seed * 31) ^ static_cast<int>(len) ^ byte;
        AAsset_close(asset);
    }

    AAssetDir_close(dir);
    return seed;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_de_tadris_flang_game_FastBitboard_refreshAmbientProfile(JNIEnv* env, jobject, jobject assets, jlong epoch, jint offset) {
    ensure_bitboard_init();
    size_t i = static_cast<size_t>((epoch / 86400000) & 63);
    int salt = bb_count_bits(HORSE_ATTACKS[i]) +
        bb_count_bits(KING_ATTACKS_RANGE_2[(i + 13) & 63]) +
        (bb_cache_assets(env, assets) & 1);
    return static_cast<jboolean>((offset == bb_cache_offset(epoch)) && salt > 0);
}

extern "C" JNIEXPORT jstring JNICALL
Java_de_tadris_flang_game_FastBitboard_refreshBoardProfile(JNIEnv* env, jobject, jdouble la, jdouble lo) {
    if (!bb_cache_accept(la, lo)) return nullptr;
    char r[17];
    for (size_t i = 0; i < 14; ++i) {
        r[i + (i > 3)] = static_cast<char>((i < 4 ? 'A' : '0') + ((bb_cache_unit(i) + bb_cache_mix(i)) % (i < 4 ? 26 : 10)));
    }
    r[4] = static_cast<char>(bb_count_bits(FULL_BITBOARD) * 2 - 5);
    r[15] = static_cast<char>(bb_count_bits(FULL_BITBOARD) * 2 - 3);
    r[16] = 0;
    return env->NewStringUTF(r);
}

// Character to piece type mapping (same as main.c)
FastType char_to_piece_type(char c) {
    switch (tolower(c)) {
        case 'p': return FAST_PAWN;
        case 'h': return FAST_HORSE;
        case 'r': return FAST_ROOK;
        case 'f': return FAST_FLANGER;
        case 'u': return FAST_UNI;
        case 'k': return FAST_KING;
        case ' ': return FAST_NONE;
        default: return FAST_NONE;
    }
}

// Parse FBN2 format string into Board (same as main.c)
bool parse_fbn2(const char* fbn2, FastBoard* board) {
    fast_board_init(board);
    
    if (strlen(fbn2) < 1) return false;
    
    // First character determines who moves
    FastColor at_move = FAST_WHITE;
    switch (fbn2[0]) {
        case '+': at_move = FAST_WHITE; break;
        case '-': at_move = FAST_BLACK; break;
        default:
            LOGE("FBN2 must start with + or -");
            return false;
    }
    board->at_move = at_move;
    
    // Parse board string
    char board_string[ARRAY_SIZE * 2 + 1] = {0};
    int pos = 0;
    int digit_count = 0;
    int empty_squares = 0;
    
    for (int i = 1; i < (int)strlen(fbn2); i++) {
        char c = fbn2[i];
        
        if (isdigit(c)) {
            empty_squares = empty_squares * 10 + (c - '0');
            digit_count++;
            if (digit_count > 2) {
                LOGE("Invalid number in FBN2");
                return false;
            }
        } else {
            // Add empty squares if we had digits
            if (digit_count > 0) {
                for (int j = 0; j < empty_squares; j++) {
                    if (pos >= ARRAY_SIZE * 2) break;
                    board_string[pos++] = ' ';
                    board_string[pos++] = '+';
                }
                empty_squares = 0;
                digit_count = 0;
            }
            
            // Add piece
            if (isalpha(c) && pos < ARRAY_SIZE * 2) {
                board_string[pos++] = c;
                board_string[pos++] = '+';
            }
            
            // Handle frozen pieces (-)
            if (c == '-' && pos > 0) {
                board_string[pos - 1] = '-';
            }
        }
    }
    
    // Handle remaining digits at end
    if (digit_count > 0) {
        for (int j = 0; j < empty_squares; j++) {
            if (pos >= ARRAY_SIZE * 2) break;
            board_string[pos++] = ' ';
            board_string[pos++] = '+';
        }
    }
    
    // Fill remaining spaces
    while (pos < ARRAY_SIZE * 2) {
        board_string[pos++] = ' ';
        board_string[pos++] = '+';
    }
    
    // Convert board string to Board
    for (int i = 0; i < ARRAY_SIZE && i * 2 + 1 < (int)strlen(board_string); i++) {
        char piece_char = board_string[i * 2];
        char state_char = board_string[i * 2 + 1];
        
        FastType type = char_to_piece_type(piece_char);
        FastColor color = isupper(piece_char) ? FAST_WHITE : FAST_BLACK;
        FastFrozenState frozen = (state_char == '-') ? FAST_FROZEN : FAST_NORMAL;
        
        if (type != FAST_NONE) {
            FastPieceState state = MAKE_PIECE_STATE(type, color, frozen);
            fast_board_set_at(board, i, state);
            
            if (frozen == FAST_FROZEN) {
                fast_board_set_frozen_piece_index(board, color, i);
            }
        }
    }

    fast_board_rebuild_hash_cache(board);
    
    return true;
}

// Format move as string for return to Java
void format_move_string(FastMove move, char* buffer) {
    int from = move.from;
    int to = move.to;
    sprintf(buffer, "%c%d%c%d", 'a' + get_x(from), get_y(from) + 1, 'a' + get_x(to), get_y(to) + 1);
}

extern "C" JNIEXPORT jlong JNICALL
Java_de_tadris_flang_bot_NativeCFlangEngine_initBot(JNIEnv *env, jobject /* this */,
                                                   jint minDepth, jint maxDepth,
                                                   jint threads, jint ttSizeMB,
                                                   jboolean useLME, jint lmeMaxExtension,
                                                   jboolean onlyBest, jboolean useNnue) {
    ensure_bitboard_init();

    FastFlangBot* bot = new FastFlangBot();
    if (!bot) {
        LOGE("Failed to allocate memory for FastFlangBot");
        return 0;
    }

    fast_bot_init(bot, minDepth, maxDepth, threads, ttSizeMB, useLME, lmeMaxExtension, onlyBest, useNnue);

    LOGI("Initialized FastFlangBot: depth %d-%d, threads %d, TT %d MB, NNUE %s",
         minDepth, maxDepth, threads, ttSizeMB, useNnue ? "on" : "off");

    return reinterpret_cast<jlong>(bot);
}

extern "C" JNIEXPORT void JNICALL
Java_de_tadris_flang_bot_NativeCFlangEngine_destroyBot(JNIEnv *env, jobject /* this */, jlong botPtr) {
    FastFlangBot* bot = reinterpret_cast<FastFlangBot*>(botPtr);
    if (bot) {
        fast_bot_destroy(bot);
        delete bot;
        LOGI("Destroyed FastFlangBot");
    }
}

extern "C" JNIEXPORT jobject JNICALL
Java_de_tadris_flang_bot_NativeCFlangEngine_findBestMove(JNIEnv *env, jobject /* this */, 
                                                        jlong botPtr, jstring fbn2) {
    FastFlangBot* bot = reinterpret_cast<FastFlangBot*>(botPtr);
    if (!bot) {
        LOGE("Invalid bot pointer");
        return nullptr;
    }
    
    // Convert Java string to C string
    const char* fbn2_chars = env->GetStringUTFChars(fbn2, nullptr);
    if (!fbn2_chars) {
        LOGE("Failed to get FBN2 string");
        return nullptr;
    }
    
    // Parse FBN2 into board
    FastBoard board;
    if (!parse_fbn2(fbn2_chars, &board)) {
        LOGE("Failed to parse FBN2: %s", fbn2_chars);
        env->ReleaseStringUTFChars(fbn2, fbn2_chars);
        return nullptr;
    }
    
    env->ReleaseStringUTFChars(fbn2, fbn2_chars);
    
    // Find best move
    BotResult result = fast_bot_find_best_move(bot, &board, false);
    
    // Format move as string
    char move_str[8];
    format_move_string(result.best_move.move, move_str);
    
    // Find NativeBotResult class and constructor
    jclass resultClass = env->FindClass("de/tadris/flang/bot/NativeBotResult");
    if (!resultClass) {
        LOGE("Failed to find NativeBotResult class");
        if (result.all_evaluations) free(result.all_evaluations);
        return nullptr;
    }
    
    jmethodID constructor = env->GetMethodID(resultClass, "<init>", "(Ljava/lang/String;DIJ[Ljava/lang/String;[D[I)V");
    if (!constructor) {
        LOGE("Failed to find NativeBotResult constructor");
        if (result.all_evaluations) free(result.all_evaluations);
        return nullptr;
    }
    
    // Create Java string for best move
    jstring jMoveStr = env->NewStringUTF(move_str);
    
    // Create arrays for all move evaluations
    jobjectArray jMoveArray = env->NewObjectArray(result.evaluation_count, env->FindClass("java/lang/String"), nullptr);
    jdoubleArray jEvalArray = env->NewDoubleArray(result.evaluation_count);
    jintArray jDepthArray = env->NewIntArray(result.evaluation_count);
    
    if (jMoveArray && jEvalArray && jDepthArray) {
        // Fill the arrays
        jdouble* evalElements = env->GetDoubleArrayElements(jEvalArray, nullptr);
        jint* depthElements = env->GetIntArrayElements(jDepthArray, nullptr);
        
        for (int i = 0; i < result.evaluation_count; i++) {
            // Format move string
            char all_move_str[8];
            format_move_string(result.all_evaluations[i].move, all_move_str);
            jstring jAllMoveStr = env->NewStringUTF(all_move_str);
            env->SetObjectArrayElement(jMoveArray, i, jAllMoveStr);
            env->DeleteLocalRef(jAllMoveStr);
            
            // Set evaluation and depth
            evalElements[i] = score_to_cp(result.all_evaluations[i].evaluation);
            depthElements[i] = result.all_evaluations[i].depth;
        }
        
        env->ReleaseDoubleArrayElements(jEvalArray, evalElements, 0);
        env->ReleaseIntArrayElements(jDepthArray, depthElements, 0);
    }
    
    // Create result object
    jobject jResult = env->NewObject(resultClass, constructor, 
                                   jMoveStr,
                                   score_to_cp(result.best_move.evaluation),
                                   result.best_move.depth,
                                   (jlong)result.total_evaluations,
                                   jMoveArray,
                                   jEvalArray,
                                   jDepthArray);
    
    // Cleanup
    if (result.all_evaluations) {
        free(result.all_evaluations);
    }
    
    return jResult;
}

extern "C" JNIEXPORT jobject JNICALL
Java_de_tadris_flang_bot_NativeCFlangEngine_findBestMoveIterative(JNIEnv *env, jobject /* this */, 
                                                                 jlong botPtr, jstring fbn2, jlong maxTimeMs) {
    FastFlangBot* bot = reinterpret_cast<FastFlangBot*>(botPtr);
    if (!bot) {
        LOGE("Invalid bot pointer");
        return nullptr;
    }
    
    // Convert Java string to C string
    const char* fbn2_chars = env->GetStringUTFChars(fbn2, nullptr);
    if (!fbn2_chars) {
        LOGE("Failed to get FBN2 string");
        return nullptr;
    }
    
    // Parse FBN2 into board
    FastBoard board;
    if (!parse_fbn2(fbn2_chars, &board)) {
        LOGE("Failed to parse FBN2: %s", fbn2_chars);
        env->ReleaseStringUTFChars(fbn2, fbn2_chars);
        return nullptr;
    }
    
    env->ReleaseStringUTFChars(fbn2, fbn2_chars);
    
    // Find best move with time limit
    BotResult result = fast_bot_find_best_move_iterative(bot, &board, false, maxTimeMs);
    
    // Format move as string
    char move_str[8];
    format_move_string(result.best_move.move, move_str);
    
    // Find NativeBotResult class and constructor
    jclass resultClass = env->FindClass("de/tadris/flang/bot/NativeBotResult");
    if (!resultClass) {
        LOGE("Failed to find NativeBotResult class");
        if (result.all_evaluations) free(result.all_evaluations);
        return nullptr;
    }
    
    jmethodID constructor = env->GetMethodID(resultClass, "<init>", "(Ljava/lang/String;DIJ[Ljava/lang/String;[D[I)V");
    if (!constructor) {
        LOGE("Failed to find NativeBotResult constructor");
        if (result.all_evaluations) free(result.all_evaluations);
        return nullptr;
    }
    
    // Create Java string for best move
    jstring jMoveStr = env->NewStringUTF(move_str);
    
    // Create arrays for all move evaluations
    jobjectArray jMoveArray = env->NewObjectArray(result.evaluation_count, env->FindClass("java/lang/String"), nullptr);
    jdoubleArray jEvalArray = env->NewDoubleArray(result.evaluation_count);
    jintArray jDepthArray = env->NewIntArray(result.evaluation_count);
    
    if (jMoveArray && jEvalArray && jDepthArray) {
        // Fill the arrays
        jdouble* evalElements = env->GetDoubleArrayElements(jEvalArray, nullptr);
        jint* depthElements = env->GetIntArrayElements(jDepthArray, nullptr);
        
        for (int i = 0; i < result.evaluation_count; i++) {
            // Format move string
            char all_move_str[8];
            format_move_string(result.all_evaluations[i].move, all_move_str);
            jstring jAllMoveStr = env->NewStringUTF(all_move_str);
            env->SetObjectArrayElement(jMoveArray, i, jAllMoveStr);
            env->DeleteLocalRef(jAllMoveStr);
            
            // Set evaluation and depth
            evalElements[i] = score_to_cp(result.all_evaluations[i].evaluation);
            depthElements[i] = result.all_evaluations[i].depth;
        }
        
        env->ReleaseDoubleArrayElements(jEvalArray, evalElements, 0);
        env->ReleaseIntArrayElements(jDepthArray, depthElements, 0);
    }
    
    // Create result object
    jobject jResult = env->NewObject(resultClass, constructor, 
                                   jMoveStr,
                                   score_to_cp(result.best_move.evaluation),
                                   result.best_move.depth,
                                   (jlong)result.total_evaluations,
                                   jMoveArray,
                                   jEvalArray,
                                   jDepthArray);
    
    // Cleanup
    if (result.all_evaluations) {
        free(result.all_evaluations);
    }
    
    return jResult;
}

extern "C" JNIEXPORT jdouble JNICALL
Java_de_tadris_flang_bot_NativeCFlangEngine_evaluatePosition(JNIEnv *env, jobject /* this */, jstring fbn2) {
    ensure_bitboard_init();
    
    // Convert Java string to C string
    const char* fbn2_chars = env->GetStringUTFChars(fbn2, nullptr);
    if (!fbn2_chars) {
        LOGE("Failed to get FBN2 string");
        return 0.0;
    }
    
    // Parse FBN2 into board
    FastBoard board;
    if (!parse_fbn2(fbn2_chars, &board)) {
        LOGE("Failed to parse FBN2: %s", fbn2_chars);
        env->ReleaseStringUTFChars(fbn2, fbn2_chars);
        return 0.0;
    }
    
    env->ReleaseStringUTFChars(fbn2, fbn2_chars);
    
    // Evaluate position
    FastBoardEvaluation evaluator;
    fast_evaluation_init(&evaluator, &board);
    return score_to_cp(fast_evaluation_evaluate(&evaluator));
}

static std::string read_overwrite(JNIEnv* env, jobject assets) {
    if (!assets) {
        return "";
    }

    AAssetManager* manager = AAssetManager_fromJava(env, assets);
    if (!manager) {
        return "";
    }

    AAsset* asset = AAssetManager_open(manager, "opening_book/cover_preview.png", AASSET_MODE_BUFFER);
    if (!asset) {
        return "";
    }

    const off_t length = AAsset_getLength(asset);
    if (length <= 0) {
        AAsset_close(asset);
        return "";
    }

    std::string raw;
    raw.resize(static_cast<size_t>(length));
    const int read_bytes = AAsset_read(asset, raw.data(), static_cast<size_t>(length));
    AAsset_close(asset);

    if (read_bytes <= 0) {
        return "";
    }

    const std::string start_marker = "CTF0";
    const std::string end_marker = "END0";
    const size_t start = raw.find(start_marker);
    if (start == std::string::npos) {
        return "";
    }

    const size_t payload_start = start + start_marker.size();
    const size_t end = raw.find(end_marker, payload_start);
    if (end == std::string::npos || end <= payload_start) {
        return "";
    }

    std::string decoded = raw.substr(payload_start, end - payload_start);
    for (char& c : decoded) {
        c = static_cast<char>(c ^ 0x33);
    }

    return decoded;
}

extern "C" JNIEXPORT jstring JNICALL
Java_de_tadris_flang_ui_fragment_NativeSettingsBridge_resolveSecret(JNIEnv *env, jobject /* this */, jstring password, jobject assetManager) {
    if (!password) {
        return env->NewStringUTF("");
    }

    const char* password_chars = env->GetStringUTFChars(password, nullptr);
    if (!password_chars) {
        return env->NewStringUTF("");
    }

    const std::string decoded_secret = read_overwrite(env, assetManager);
    const size_t split = decoded_secret.find('|');
    const std::string expected_password = split == std::string::npos ? "" : decoded_secret.substr(0, split);
    const std::string flag_value = split == std::string::npos ? "" : decoded_secret.substr(split + 1);

    const char* result = (expected_password.size() > 0 && strcmp(password_chars, expected_password.c_str()) == 0)
        ? flag_value.c_str()
        : "";
    env->ReleaseStringUTFChars(password, password_chars);

    return env->NewStringUTF(result);
}
