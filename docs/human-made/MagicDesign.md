# **Magic Notes and Ideas**

## Warhammer Fantasy Concepts

- The source of magic is a tear in reality at the north pole called the Chaos Gate\. It causes the Aethyr's chaotic energy to leak into reality\. When it does so, reality forces physical laws onto it, refracting it into the eight winds with different affinities\.
    - This means that the local strength of each wind is dependent on how close to the chaos gate it is, how well the local environment suits each wind's affinities, including things like time of day\.
- The Great Vortex of Ulthuan is an ancient elven ritual that drains magic out of the world to prevent the Aethyr's transforming energy from destroying the world\.
- The eight winds
    - Hysh \- Light
    - Chamon \- Alchemy
    - Ghyran \- Life
    - Azyr \- Heavens
    - Ulgu \- Shadow
    - Shyish \- Death
    - Aqshy \- Fire
    - Ghur \- Beasts
- Other kinds of magic
    - High Magic
        - AKA True Magic, AKA Qhaysh
        - Exclusive to High Elves, in the context of the game it should only be a story element\. 
        - It should be both powerful and mystical, something beyond the scope of what a player can ever do 
            - \(except as a treat for 100%ing the game?\)
- Dark Magic
    - AKA Dhar
    - Dark Magic is similar to High Magic in that it blends the various aspects of many different Winds, but it differs in that it is corrupted, polluted even\. Dark Magic is wild and unpredictable, resulting in terrible and unexpected side effects\.
- True Dhar can form naturally in areas where the winds are stagnant, and the Great Vortex can't reach, the result of all the colors of magic in a particular area swirling and merging together under the pressure of physical reality, coagulating and stagnating in a metaphysical sense\.
    - To those with witchsight unfortunate enough to have witnessed it, True Dhar is said to flow like sluggish tar, and any being it is drawn to will have their minds and souls slowly drown in its black and sticky depths\.

## In\-game adaptation

## Chunk Wind Levels

Each wind is attracted to or repelled by an area's different environmental factors\. 

Ideas for how to implement this in\-game:

- Each chunk keeps track of it's individual wind levels, with a current level, and a baseline that it naturally returns to\.
    - Natural influence
        - Distance to Chaos Portal \(or just gaussian blur background levels if no portal\)
            - Affects global wind baseline levels \- Closer = higher
        - Distance to Great Vortex \(or just gaussian blur background levels if no vortex\)
            - Affects how fast all winds return to baseline \- Closer = faster?
            - Affects how hard it is to change the baseline \- Closer = harder
        - Biome type affects the baseline levels for specific winds \- e\.g\. Warm Ocean biome has increased baseline green and red, and reduced yellow
        - Time of Day \- White is elevated during the day, and Grey is high during the night
        - Tagged blocks in the chunk
    - Player control
        - Tier 0: the player decorates with tagged mundane blocks to change the baseline wind levels
        - Tier 1: The player can cast basic spells, removing wind from the chunk
        - Tier 2: The player can create magic blocks that artificially inflate a specific wind's baseline capacity and replenishment speed
        - Tier 3: The player can create wind\-specific power stones in high wind areas, reducing their dependency on local conditions to cast spells\. These stones also leak wind when in the player's inventory, so masters should affect the winds just by walking around\.

## Wind of Magic effects

Most of the time, baseline levels should not affect anything, but when a particular wind's level reaches certain thresholds, certain effects should begin manifesting in the world so that even the untrained can tell magic energies are at play\. Nothing unusual happens at extremely low levels, other than spells of that school failing

The maximum amount of each wind is arbitrarily high, the minimum is zero\. To keep things simple, maybe only the top 2 or three winds in an area show their effects, and if four or more winds are high enough, they mix into dark magic effects

## Player Attributes

Since most of this comes from the tabletop game, we should consider adapting skills and 'Magic Characteristic' somehow into player attributes\. 

## <br>Research system \- Modonomicon?

- Foundation/Tutorial tab
- Tabs for each wind
    - Each wind has a tier
        - Apprentice
        - Journeyman
        - Master
        - Wizard Lord
    - Spells gained through research
        - Each School has a lore spell list, and WFRP players can only choose one, not sure if this is worth implementing for more than flavor\.
            - Elemental
            - Mystical
            - Cardinal
    - Magic Machines recipes
        - Chamon has the alchemy lab for example
        - Aqshy might have a blast furnace that runs on wind to work faster or double products
    - Power Stones
        - Only the most powerful and experienced Magisters of the Orders can even attempt to create stones of power\. The ability to create one shows mastery over the school of magic\.
        - Allows the storage of a specific wind's energy, if only temporarily
        - One for each school
            - True Sapphires \- Azyr
            - Endstones \- Shyish
            - Ghost Amber \- Ghur
            - Lumen Stones \- Hysh
            - Fire Rubies \- Aqshy
            - Goldstone \- Chamon
            - Crystal Mist \- Ulgu
            - Vitaellum \- Ghyran
        - High Magic stones exist
- High Magic?
- Dark Magic tab?
    - Should only be unlocked after reaching tier 2 in multiple schools of magic, and then they must meet other requirements
- Runesmithing tab
    - Should require some kind of Dwarven relic or knowedge to unlock the tab
- Alchemy might need its own tab even though it's discovered through Chamon
- 

## Familiars

Might not be worth implementing, but it felt cool enough to include\. Imagine having a little homunclulus in your base\.<br>

### Creation

Creating a familiar is a three\-step process\.

1. Gather arcane components
2. Time and effort imbuing the creature with magic
3. Testing the result 
    1. generate a type of familiar
    2. generate unusual physical characteristics
    3. generate relationship
    4. generate personality

### Binding

Instead of creating one, a mage can instead bind a creature to create a familiar\.

This is also a three step process

1. Find it
    1. Must be a neutral or friendly mob
2. Spend time with it
    1. Item to start the attunement process
    2. 1d10/2 days later, then it becomes attuned
    3. if the player spent more than an ingame hour or two away from the mob they must start over
    4. After the wait, a random skill check determines success, otherwise wait 1d10/2 more days
3. Test it
    1. generate a relationship
    2. Generate a personality

## Potions

Vanilla potions and brewing stands are considered high magic, safe to use, but the mechanisms behind the brewing stand and differences between our potions are unknown\.

Warhammer potions are defined by these characteristics:

- Name
- Effect
- Lag Time
- Volatility
- Ingredient Cost
- Ingredient Locale
- Ingredient Difficulty
- Creation Number
- Creation Time

### Brewing Potions

- Requirements
    - An alchemical laboratory\. 
        - These can be of poor, good, or best quality\. Tier 1, 2, or 3 Chamon research unlocks the recipes for each quality lab\.
    - Obtain the recipe for the potion you wish to brew\.
        - Recipes are items that are inserted into the lab to set the type of potion\.
        - Ideas for how to obtain:
            - Max level Villager trades
            - Rare Dungeon or raid loot
            - One\-time research rewards for the basic ones?
        - Can be crafted with paper to copy
- Ingredients
    - We have a lot of leeway here to do whatever we want here\.
    - Ideas:
        - Randomized ingredients for each batch
        - Biome tags for ingredients \(e\.g\. the X potion needs 3\-7 unique items from the X biome\)
- Brewing
    - Takes time based on recipe
    - When finished, make a test based on difficulty, and create Xd10/2 potions, where X is the degrees of success
    - If there is no success, a brewing disaster happens\.

# Rune Magic

Basically a form of enchantment

## Rules:

- Rule of Form: Runes must be inscribed on items of the appropriate type\.
- Rule of Three: A single item can only have up to three runes on it\.
- Rule of Mastery: Master runes are too powerful to be combined with other runes\. If an item is inscribed with a master rune, it can bear no other runes of any kind\. 
    - Runesmiths refer to master runes as "jealous runes" for this reason\.
- Rule of Pride:  a Runesmith will never create a copy of a rune item he's made before\.
    - Maybe we ignore this one, or implement it so that there's a cooldown or penalty instead
- Rule of Time: A Runesmith can only work on one rune at a time

## Rune Properties:

- Type: Determines what the rune can be applied to
    - Armor, weapon, talismanic, engineering, etc\.
- Temporary or Permanent
    - Temp is easier but one use only
    - Permanent requires Tier 2 and is more expensive
- Inscription Number
    - How many steps it takes to make
- Empowerment
    - How hard each Runecraft skill check is

