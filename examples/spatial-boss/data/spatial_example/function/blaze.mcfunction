mineturn ai target nearest_enemy
execute store result score @s mineturn.ai run mineturn ai query ready spatial_example:flare
execute if score @s mineturn.ai matches 1 run return run mineturn ai use spatial_example:flare
function mineturn:ai/melee/turn {action:"mineturn:melee"}
