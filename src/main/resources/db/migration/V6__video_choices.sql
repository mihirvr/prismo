-- V6 (P8): what the user picked for each video, so it comes back the next time the video opens: the sound track,
-- the subtitles and the subtitle delay. One row per video, only once something was picked; a column left NULL was not
-- picked (the app then prefers the language picked last, else the file's own default).
--
-- ON DELETE CASCADE: when the video's row goes (file deleted, folder removed, delete permanently) its row here goes
-- with it. A rename or move keeps the media row's id (the indexer and FileOperations update the path), so the
-- choice follows the file. Needs PRAGMA foreign_keys = ON, which every Prismo connection sets.

CREATE TABLE video_choices (
    media_id              INTEGER NOT NULL PRIMARY KEY REFERENCES media (id) ON DELETE CASCADE,
    audio_stream          INTEGER CHECK (audio_stream IS NULL OR audio_stream >= 0),  -- FFmpeg's stream number
    subtitle              TEXT    CHECK (subtitle IS NULL OR subtitle IN ('off', 'track', 'file')),
    subtitle_stream       INTEGER CHECK (subtitle_stream IS NULL OR subtitle_stream >= 0),
    subtitle_file         TEXT,                     -- a name in the video's folder, or a full path
    subtitle_delay_micros INTEGER NOT NULL DEFAULT 0,
    CHECK (subtitle IS NOT 'track' OR subtitle_stream IS NOT NULL),
    CHECK (subtitle IS NOT 'file' OR subtitle_file IS NOT NULL)
) STRICT;
