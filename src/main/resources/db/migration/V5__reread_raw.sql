-- V5 (P7): read every RAW file's metadata again. Until P7 the reader looked only at the first Exif SubIFD, and many RAW
-- files (NEF, ARW, DNG) keep the date taken and the picture size in a later one; CR3 files gave no EXIF at all.
-- The indexer re-reads a file whose size or modified time differs from its row, so a size of 0 makes the next index
-- run read each RAW file once more (no real RAW file is empty). The row keeps its id, favorite, hidden and album
-- data, and the thumbnail stays valid: it depends only on the modified time, which is not touched.

UPDATE media SET size_bytes = 0 WHERE media_type = 'raw';
