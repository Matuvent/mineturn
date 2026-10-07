execute store result score @s mineturn.ai run random value 1..100
execute if score @s mineturn.ai matches 31.. run return 0
execute store result score @s mineturn.ai run mineturn ai query ready mineturn:witch_slowness
execute if score @s mineturn.ai matches 1 run return run mineturn ai use mineturn:witch_slowness
