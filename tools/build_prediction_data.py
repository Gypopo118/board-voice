"""Build the on-device prediction assets: frequency word list and bigram table.

Sources
-------
* wordfreq (https://github.com/luminoso/wordfreq, Apache-2.0) for balanced Russian and English
  word frequencies. It merges Wikipedia, news, web and subtitle corpora, which keeps common
  geography and everyday words at a sane rank instead of a subtitles-only skew.
* The FrequencyWords-derived ``ru_dict.tsv`` that is already in the repository, used only as a
  source of "ё" spellings: corpora that normalise ``ё`` to ``е`` would otherwise hide words such
  as ``ёлка``.
* Tatoeba Russian sentences (https://tatoeba.org, CC-BY 2.0 FR) for bigram counts.

Two curated layers are applied on top of the corpus:
* common hyphenated words (``что-то``, ``как-нибудь``, ``из-за``, ``во-первых``) and the removal of
  their glued corpus spellings (``чтото``, ``какнибудь``);
* a proper-noun display table (city names and their case forms) emitted as ``ru_proper.tsv``.
  The frequency list stays lowercase and case-neutral; the keyboard only capitalises a candidate
  when it really is a proper name.

Run with a virtualenv that has ``wordfreq`` installed::

    python -m pip install wordfreq
    python tools/build_prediction_data.py --sentences rus_sentences.tsv.bz2

``--curated-only`` re-applies just the curated layers to the assets already in the repository:
it needs neither wordfreq nor the sentence export.

The generated files are committed; the Android build never needs Python or the network.
"""
from __future__ import annotations

import argparse
import bz2
import re
from collections import Counter
from pathlib import Path

RU_WORD = re.compile(r"^[а-яё]+(?:-[а-яё]+){0,2}$")
EN_WORD = re.compile(r"^[a-z]{2,}$")
RU_SINGLE = {"а", "в", "и", "к", "о", "с", "у", "я"}
EN_SINGLE = {"a", "i"}
TOKEN = re.compile(r"[а-яё]+")

MAX_WORD_LENGTH = 32

# Prepositions and pronouns whose next word is worth storing deeper than the global cut.
FUNCTION_WORDS = {
    "в", "во", "на", "из", "с", "со", "к", "ко", "по", "о", "об", "обо", "у", "за", "для",
    "от", "до", "при", "без", "под", "над", "про", "через", "между", "около", "после",
    "перед", "вместо", "кроме",
    "и", "а", "но", "что", "как", "не", "это", "он", "она", "они", "мы", "ты", "вы", "я",
    "его", "ее", "их", "мне", "тебя", "нас", "вас", "себя", "весь", "все", "этот", "тот",
    "мой", "твой", "свой", "наш", "ваш", "был", "была", "было", "были", "будет", "есть",
}

FUNCTION_WORD_DEPTH = 200

# ---------------------------------------------------------------------------------------------
# Curated Russian layer: hyphenated words and proper-noun display forms.
#
# The corpus list stores the glued spellings ("чтото", "както", "всетаки") that web text contains
# and that make gesture typing emit words without a hyphen. They are removed, and the normal
# literary forms are added with corpus-like weights. A hyphen has no key on the letter layout, so
# the swipe decoder treats it as a silent separator between letters.
# ---------------------------------------------------------------------------------------------

MERGED_PARTICLE_BASES = (
    "что", "кто", "где", "как", "какой", "какая", "какое", "какие", "какого", "какому",
    "каким", "каком", "когда", "куда", "откуда", "зачем", "почему", "сколько",
    "чей", "чья", "чьё", "чьи", "чего", "чему", "чем", "кому", "кем", "кого",
    "всё", "все", "так",
)
MERGED_PARTICLES = ("то", "нибудь", "либо", "таки")

# Rough corpus-scale weights: they keep the common forms in the top-3 of their prefix while the
# frequency list still decides between, say, "что" and "что-то".
HYPHENATED_FREQUENCIES = {
    "что-то": 900_000,
    "кто-то": 450_000,
    "где-то": 400_000,
    "как-то": 650_000,
    "какой-то": 450_000,
    "какая-то": 220_000,
    "какое-то": 170_000,
    "какие-то": 200_000,
    "какого-то": 60_000,
    "какому-то": 30_000,
    "каким-то": 70_000,
    "каком-то": 45_000,
    "когда-то": 300_000,
    "куда-то": 130_000,
    "откуда-то": 90_000,
    "зачем-то": 50_000,
    "почему-то": 350_000,
    "сколько-то": 35_000,
    "чей-то": 15_000,
    "чья-то": 6_000,
    "чьё-то": 3_000,
    "чьи-то": 8_000,
    "чего-то": 280_000,
    "чему-то": 35_000,
    "чем-то": 220_000,
    "кому-то": 190_000,
    "кем-то": 110_000,
    "кого-то": 280_000,
    "что-нибудь": 380_000,
    "кто-нибудь": 240_000,
    "где-нибудь": 190_000,
    "как-нибудь": 220_000,
    "какой-нибудь": 160_000,
    "какая-нибудь": 55_000,
    "какое-нибудь": 45_000,
    "какие-нибудь": 55_000,
    "какого-нибудь": 20_000,
    "каким-нибудь": 22_000,
    "когда-нибудь": 190_000,
    "куда-нибудь": 55_000,
    "откуда-нибудь": 25_000,
    "почему-нибудь": 2_000,
    "сколько-нибудь": 30_000,
    "чей-нибудь": 2_000,
    "чего-нибудь": 120_000,
    "кому-нибудь": 60_000,
    "кем-нибудь": 30_000,
    "чем-нибудь": 80_000,
    "кого-нибудь": 100_000,
    "что-либо": 60_000,
    "кто-либо": 40_000,
    "где-либо": 30_000,
    "как-либо": 12_000,
    "какой-либо": 70_000,
    "какая-либо": 20_000,
    "какое-либо": 18_000,
    "какие-либо": 20_000,
    "когда-либо": 70_000,
    "куда-либо": 12_000,
    "откуда-либо": 5_000,
    "чего-либо": 35_000,
    "кому-либо": 12_000,
    "кем-либо": 8_000,
    "чем-либо": 25_000,
    "кого-либо": 18_000,
    "всё-таки": 300_000,
    "опять-таки": 60_000,
    "довольно-таки": 20_000,
    "по-моему": 160_000,
    "по-твоему": 40_000,
    "по-вашему": 45_000,
    "по-нашему": 25_000,
    "по-своему": 65_000,
    "по-прежнему": 110_000,
    "по-новому": 45_000,
    "по-старому": 30_000,
    "по-русски": 90_000,
    "по-английски": 35_000,
    "по-немецки": 12_000,
    "по-французски": 12_000,
    "по-испански": 6_000,
    "по-китайски": 4_000,
    "по-настоящему": 80_000,
    "по-разному": 60_000,
    "по-хорошему": 40_000,
    "по-плохому": 10_000,
    "по-дружески": 8_000,
    "по-особенному": 15_000,
    "во-первых": 130_000,
    "во-вторых": 110_000,
    "в-третьих": 55_000,
    "в-четвёртых": 16_000,
    "в-пятых": 8_000,
    "кое-кто": 60_000,
    "кое-что": 90_000,
    "кое-где": 25_000,
    "кое-как": 45_000,
    "кое-какой": 15_000,
    "из-за": 800_000,
    "из-под": 90_000,
    "чуть-чуть": 120_000,
    "еле-еле": 35_000,
    "давным-давно": 25_000,
    "давно-давно": 12_000,
    "вот-вот": 160_000,
    "точь-в-точь": 12_000,
    "мало-помалу": 6_000,
    "тихо-тихо": 12_000,
    # Hyphenated toponyms are tokens too: without them "Санкт-Петербург" could only be typed
    # letter by letter and never appeared in the T9 panel.
    "санкт-петербург": 60_000,
    "ростов-на-дону": 40_000,
    "улан-удэ": 8_000,
    "комсомольск-на-амуре": 5_000,
    "петропавловск-камчатский": 5_000,
    "ханты-мансийск": 5_000,
    "южно-сахалинск": 4_000,
    "йошкар-ола": 3_000,
    "каменск-уральский": 3_000,
    "орехово-зуево": 3_000,
    "наро-фоминск": 2_000,
    "переславль-залесский": 2_000,
}

# Single-word city names. The list intentionally leaves out names that are homonyms of common
# nouns ("орёл" bird, "клин" wedge, "бор" forest, "шахты" mines, "видное" prominent, "грозный"
# menacing): capitalising those mid-sentence would corrupt ordinary text.
CITIES = (
    "Москва", "Санкт-Петербург", "Петербург", "Новосибирск", "Екатеринбург", "Казань",
    "Челябинск", "Красноярск", "Самара", "Уфа", "Ростов-на-Дону", "Омск", "Краснодар",
    "Воронеж", "Пермь", "Волгоград", "Саратов", "Тюмень", "Тольятти", "Барнаул",
    "Ижевск", "Ульяновск", "Иркутск", "Хабаровск", "Ярославль", "Владивосток",
    "Махачкала", "Томск", "Оренбург", "Кемерово", "Новокузнецк", "Рязань",
    "Астрахань", "Пенза", "Липецк", "Киров", "Чебоксары", "Тула", "Калининград",
    "Балашиха", "Курск", "Севастополь", "Сочи", "Ставрополь", "Улан-Удэ", "Тверь",
    "Магнитогорск", "Иваново", "Брянск", "Симферополь", "Белгород", "Сургут",
    "Владимир", "Архангельск", "Чита", "Калуга", "Смоленск", "Курган",
    "Череповец", "Орск", "Саранск", "Владикавказ", "Якутск", "Мурманск", "Тамбов",
    "Стерлитамак", "Кострома", "Петрозаводск", "Нижневартовск", "Новороссийск",
    "Йошкар-Ола", "Химки", "Таганрог", "Сыктывкар", "Нальчик", "Дзержинск",
    "Братск", "Ангарск", "Благовещенск", "Энгельс", "Псков", "Бийск",
    "Прокопьевск", "Балаково", "Рыбинск", "Северодвинск", "Армавир", "Подольск",
    "Абакан", "Норильск", "Сызрань", "Новочеркасск", "Волгодонск", "Златоуст",
    "Уссурийск", "Домодедово", "Мытищи", "Люберцы", "Красногорск", "Одинцово",
    "Коломна", "Дмитров", "Щёлково", "Раменское", "Долгопрудный", "Реутов",
    "Жуковский", "Пушкино", "Ногинск", "Дубна", "Егорьевск", "Воскресенск",
    "Серпухов", "Наро-Фоминск", "Лыткарино", "Фрязино", "Ивантеевка", "Бронницы",
    "Апрелевка", "Голицыно", "Кубинка", "Троицк", "Новгород", "Азов", "Кинешма",
    "Шуя", "Новочебоксарск", "Нефтекамск", "Нефтеюганск", "Первоуральск", "Миасс",
    "Копейск", "Ноябрьск", "Обнинск", "Вологда", "Черемхово", "Канск", "Ачинск",
    "Назрань", "Каспийск", "Дербент", "Майкоп", "Черкесск", "Кызыл", "Биробиджан",
    "Салехард", "Воркута", "Кунгур", "Березники", "Соликамск", "Ревда",
    "Серов", "Полевской", "Ханты-Мансийск", "Южно-Сахалинск", "Каменск-Уральский",
    "Орехово-Зуево", "Переславль-Залесский", "Комсомольск-на-Амуре",
    "Петропавловск-Камчатский",
)

# Compound names whose case forms cannot be produced by declining only the last component.
EXPLICIT_CITY_FORMS = {
    "Ростов-на-Дону": (
        "Ростов-на-Дону", "Ростова-на-Дону", "Ростову-на-Дону", "Ростове-на-Дону",
        "Ростовом-на-Дону",
    ),
    "Комсомольск-на-Амуре": (
        "Комсомольск-на-Амуре", "Комсомольска-на-Амуре", "Комсомольску-на-Амуре",
        "Комсомольске-на-Амуре", "Комсомольском-на-Амуре",
    ),
    "Петропавловск-Камчатский": (
        "Петропавловск-Камчатский", "Петропавловска-Камчатского",
        "Петропавловску-Камчатскому", "Петропавловске-Камчатском",
        "Петропавловском-Камчатским",
    ),
}


def zipf_to_frequency(zipf: float) -> int:
    """A count-like integer weight; preserves the order and spread of the zipf scores."""
    return max(1, int(round(10.0 ** zipf)))


def normalize_yo(word: str) -> str:
    return word.replace("ё", "е")


def load_yo_spellings(path: Path) -> dict[str, str]:
    """ё-spelling -> е-normalised spelling, from the previous FrequencyWords asset."""
    variants: dict[str, str] = {}
    if not path.exists():
        return variants
    for line in path.read_text(encoding="utf-8").splitlines():
        word = line.split("\t", 1)[0].strip().lower()
        if "ё" in word and RU_WORD.fullmatch(word):
            variants[word] = normalize_yo(word)
    return variants


def build_russian(limit: int, yo_source: Path) -> tuple[dict[str, float], dict[str, str]]:
    """Returns word -> zipf and normalised е-form -> preferred ё spelling."""
    import wordfreq

    top = wordfreq.top_n_list("ru", limit * 3)
    zipf: dict[str, float] = {}
    for word in top:
        word = word.lower()
        if not (RU_WORD.fullmatch(word) or word in RU_SINGLE):
            continue
        if len(word) > MAX_WORD_LENGTH:
            continue
        value = wordfreq.zipf_frequency(word, "ru")
        if value > 0:
            zipf[word] = value

    # Keep the best `limit` forms, then make sure the ё spellings of those forms are present so
    # typing "е" can find them without an edit-distance penalty.
    ranked = sorted(zipf.items(), key=lambda item: (-item[1], item[0]))[:limit]
    zipf = dict(ranked)

    yo_by_normalized: dict[str, str] = {}
    for yo, plain in load_yo_spellings(yo_source).items():
        if plain in zipf:
            yo_by_normalized[plain] = yo
    # wordfreq's own top list may also contain ё forms whose е twin is outside the cut.
    for word in list(zipf):
        if "ё" in word:
            yo_by_normalized.setdefault(normalize_yo(word), word)

    for plain, yo in list(yo_by_normalized.items()):
        base = max(zipf.get(plain, 0.0), zipf.get(yo, 0.0))
        if base <= 0:
            continue
        # The same weight for both spellings: the usual corpus writes "е" for "ё", so the ё form
        # must not be punished for a difference the user did not make.
        zipf[plain] = base
        zipf[yo] = base
    return zipf, yo_by_normalized


def build_english(limit: int) -> dict[str, float]:
    import wordfreq

    top = wordfreq.top_n_list("en", limit * 3)
    zipf: dict[str, float] = {}
    for word in top:
        word = word.lower()
        if not (EN_WORD.fullmatch(word) or word in EN_SINGLE):
            continue
        if len(word) > MAX_WORD_LENGTH:
            continue
        value = wordfreq.zipf_frequency(word, "en")
        if value > 0:
            zipf[word] = value
        if len(zipf) >= limit:
            break
    return zipf


def write_dictionary(path: Path, zipf: dict[str, float]) -> None:
    write_frequencies(path, {word: zipf_to_frequency(value) for word, value in zipf.items()})


def write_frequencies(path: Path, frequencies: dict[str, int]) -> None:
    words = sorted(frequencies.items(), key=lambda item: (-item[1], item[0]))
    path.write_text(
        "".join(f"{word}\t{frequency}\n" for word, frequency in words),
        encoding="utf-8",
    )


def read_frequencies(path: Path) -> dict[str, int]:
    frequencies: dict[str, int] = {}
    if not path.exists():
        return frequencies
    for line in path.read_text(encoding="utf-8").splitlines():
        parts = line.split("\t")
        if len(parts) < 2 or not parts[0]:
            continue
        try:
            frequency = int(parts[1])
        except ValueError:
            continue
        if frequency > 0:
            frequencies[parts[0]] = frequency
    return frequencies


def apply_curated_russian(frequencies: dict[str, int]) -> dict[str, int]:
    """Drops glued particle spellings and installs the curated hyphen vocabulary.

    Web corpora write ``чтото`` and ``всетаки`` as one token, and the swipe decoder then emits
    exactly those spellings because no key produces a hyphen. Hyphenated forms outside the
    curated list are dropped too: a hand-checked vocabulary keeps gesture typing predictable.
    """
    result = dict(frequencies)
    merged = {
        base + particle
        for base in MERGED_PARTICLE_BASES
        for particle in MERGED_PARTICLES
    }
    for word in merged:
        result.pop(word, None)
    for word in list(result):
        if "-" in word and word not in HYPHENATED_FREQUENCIES:
            del result[word]
    for word, frequency in HYPHENATED_FREQUENCIES.items():
        # Web corpora also glue the whole compound ("изза", "санктпетербург"); those spellings
        # are never correct Russian and used to appear in the swipe alternatives.
        result.pop(word.replace("-", ""), None)
        result[word] = frequency
    return result


def decline_city(name: str) -> list[str]:
    """Nominative plus the common oblique cases of one city name.

    This is a deliberately small declarative rule set, not a morphology engine: the curated city
    list is short and only unambiguous noun names are included. Hyphenated names decline their
    last component ("Санкт-Петербурга"), except for the explicit compound table.
    """
    if name in EXPLICIT_CITY_FORMS:
        return list(EXPLICIT_CITY_FORMS[name])
    if " " in name:
        return [name]
    parts = name.split("-")
    tail = parts[-1]
    prefix = parts[:-1]
    down = tail.lower()
    last = down[-1]
    if last == "а":
        stem = tail[:-1]
        stem_down = down[:-1]
        genitive = stem + ("и" if stem_down[-1] in "гкхжчшщц" else "ы")
        forms = [tail, genitive, stem + "е", stem + "у", stem + "ой"]
    elif last == "я":
        stem = tail[:-1]
        stem_down = down[:-1]
        genitive = stem + "и"
        prepositional = stem + ("и" if stem_down.endswith("и") else "е")
        forms = [tail, genitive, stem + "и", prepositional, stem + "ю", stem + "ей"]
    elif last == "ь":
        stem = tail[:-1]
        forms = [tail, stem + "и", stem + "ью"]
    elif down.endswith("ый"):
        stem = tail[:-2]
        forms = [tail, stem + "ого", stem + "ому", stem + "ом", stem + "ым"]
    elif down.endswith("ий"):
        stem = tail[:-2]
        forms = [tail, stem + "его", stem + "ему", stem + "ем", stem + "им"]
    elif down.endswith("ой"):
        stem = tail[:-2]
        forms = [tail, stem + "ого", stem + "ому", stem + "ом", stem + "ым"]
    elif last in "аеёиоуыэюяй":
        # "Кемерово", "Тольятти", "Сочи", "Улан-Удэ" and plural names do not decline as nouns.
        forms = [tail]
    elif last == "ц":
        forms = [tail, tail + "а", tail + "у", tail + "е"]
    else:
        forms = [tail, tail + "а", tail + "у", tail + "ом", tail + "е"]
    unique = list(dict.fromkeys(forms))
    return ["-".join(prefix + [form]) for form in unique]


def build_proper_nouns(words: set[str]) -> dict[str, str]:
    """Lowercase lookup key -> properly capitalised display form, limited to known words."""
    proper: dict[str, str] = {}
    for city in CITIES:
        for form in decline_city(city):
            key = normalize_yo(form.lower())
            if key in words:
                proper[key] = form
    return proper


def write_proper_nouns(path: Path, proper: dict[str, str]) -> None:
    path.write_text(
        "".join(f"{key}\t{proper[key]}\n" for key in sorted(proper)),
        encoding="utf-8",
    )


def build_bigrams(
    sentences: Path,
    russian: dict[str, float],
    limit: int,
    minimum_count: int,
    function_words: set[str],
    function_word_depth: int,
) -> list[tuple[str, str, int]]:
    """Adjacent-word counts over Tatoeba, restricted to dictionary words.

    The general cut keeps the most frequent pairs. Common prepositions and pronouns get a deeper
    cut too: case disambiguation needs pairs such as ``из москвы`` that sit far below the global
    top by raw count but are the strongest signal for the next word form.
    """
    known = set(russian)
    counts: Counter[tuple[str, str]] = Counter()
    with bz2.open(sentences, "rt", encoding="utf-8") as source:
        for line in source:
            columns = line.rstrip("\n").split("\t")
            if len(columns) < 3:
                continue
            words = TOKEN.findall(columns[2].lower())
            for left, right in zip(words, words[1:]):
                left = normalize_yo(left)
                right = normalize_yo(right)
                if left in known and right in known:
                    counts[(left, right)] += 1

    selected: dict[tuple[str, str], int] = {}
    for (left, right), count in counts.most_common(limit * 2):
        if count >= minimum_count:
            selected[(left, right)] = count
        if len(selected) >= limit:
            break

    for previous in function_words:
        next_words = sorted(
            ((right, count) for (left, right), count in counts.items() if left == previous),
            key=lambda item: (-item[1], item[0]),
        )[:function_word_depth]
        for right, count in next_words:
            if count >= minimum_count:
                selected[(previous, right)] = count

    return sorted(
        ((left, right, count) for (left, right), count in selected.items()),
        key=lambda item: (item[0], -item[2], item[1]),
    )


def write_bigrams(path: Path, pairs: list[tuple[str, str, int]]) -> None:
    path.write_text(
        "".join(f"{left}\t{right}\t{count}\n" for left, right, count in pairs),
        encoding="utf-8",
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--sentences", type=Path,
                        help="Tatoeba rus_sentences.tsv.bz2 (required for a full rebuild)")
    parser.add_argument("--output", type=Path, default=Path("app/src/main/assets"))
    parser.add_argument("--ru-limit", type=int, default=150_000)
    parser.add_argument("--en-limit", type=int, default=60_000)
    parser.add_argument("--bigram-limit", type=int, default=25_000)
    parser.add_argument("--bigram-min-count", type=int, default=3)
    parser.add_argument("--yo-source", type=Path,
                        default=Path("app/src/main/assets/ru_dict.tsv"))
    parser.add_argument(
        "--curated-only",
        action="store_true",
        help="Only re-apply the curated Russian layers to the committed assets",
    )
    args = parser.parse_args()

    args.output.mkdir(parents=True, exist_ok=True)
    if args.curated_only:
        russian = apply_curated_russian(read_frequencies(args.output / "ru_dict.tsv"))
        write_frequencies(args.output / "ru_dict.tsv", russian)
        proper = build_proper_nouns(set(russian))
        write_proper_nouns(args.output / "ru_proper.tsv", proper)
        print(f"ru={len(russian)} hyphenated={len(HYPHENATED_FREQUENCIES)} proper={len(proper)}")
        return

    if args.sentences is None:
        parser.error("--sentences is required unless --curated-only is used")
    russian_zipf, yo = build_russian(args.ru_limit, args.yo_source)
    english = build_english(args.en_limit)
    russian = apply_curated_russian(
        {word: zipf_to_frequency(value) for word, value in russian_zipf.items()}
    )
    write_frequencies(args.output / "ru_dict.tsv", russian)
    write_dictionary(args.output / "en_dict.tsv", english)
    proper = build_proper_nouns(set(russian))
    write_proper_nouns(args.output / "ru_proper.tsv", proper)
    pairs = build_bigrams(
        args.sentences,
        russian,
        args.bigram_limit,
        args.bigram_min_count,
        FUNCTION_WORDS,
        FUNCTION_WORD_DEPTH,
    )
    write_bigrams(args.output / "ru_bigrams.tsv", pairs)
    print(
        f"ru={len(russian)} (yo={sum(1 for w in russian if 'ё' in w)}, twins={len(yo)}, "
        f"proper={len(proper)}) en={len(english)} bigrams={len(pairs)}"
    )


if __name__ == "__main__":
    main()
