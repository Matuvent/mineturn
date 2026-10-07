execute store result score @s mineturn.ai run mineturn ai target nearest_enemy
execute if score @s mineturn.ai matches 0 run return run mineturn ai wait
execute store result score @s mineturn.ai run mineturn ai query ready mineturn:phantom_telegraph
execute if score @s mineturn.ai matches 1 run return run mineturn ai use mineturn:phantom_telegraph
execute store result score @s mineturn.ai run mineturn ai query height_difference
execute if score @s mineturn.ai matches ..1499 run return run function mineturn:ai/phantom/climb
return run mineturn ai move_async toward_target
