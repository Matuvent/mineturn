tag @s add mt_turn
execute if entity @s[tag=mt_loop] run return run function mineturn_test:loop
mineturn ai target nearest_enemy
execute if entity @s[tag=mt_walk] run return run function mineturn_test:walk
execute store result score @s mt_spoof run execute as @p run mineturn ai use mineturn_test:strike
function mineturn_test:attack
